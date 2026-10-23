# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Consistency check between Repsy's database and its storage (REFERENCE section 6, real names).

Lists the stored objects (the repsy container's storage directory, or the S3 bucket through
amazon/aws-cli) and the rows that should match them, and prints one JSON object:

  maven: pom_without_row   a stored POM whose version has no maven_artifact_version row
         row_without_pom   a version row whose POM is not stored (ghost: listed, not downloadable)
         jar_without_pom   a version directory with a jar but no POM (invisible in the panel)
  npm:   tgz_without_row   a stored tarball without an npm_package_version row (orphan)
         row_without_tgz   a version row without its tarball (ghost)
  usage: disk_usage minus the stored bytes, per repo (drift of the usage counter)
  leftovers: .repsy-write-*.tmp files (fs) or incomplete multipart uploads (s3)

Usage: python3 chaos/check.py fs|s3   (run from case-study/, the stack must be up)
"""
import json
import os
import re
import subprocess
import sys

AWS_IMAGE = "amazon/aws-cli@sha256:c8156a0e74e13929a42d7e60f0d4e14e7d28d980cd21822505d4bff80e5460c1"


def sh(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def env_value(key):
    for line in open(".env"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    raise KeyError(key)


def psql(query):
    out = sh("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "repsy", "-At",
             "-F", "|", "-c", query)
    return [line.split("|") for line in out.splitlines() if line]


def aws(*args):
    return sh("docker", "run", "--rm", "--network", "repsy-cs_default",
              "-e", "AWS_ACCESS_KEY_ID=" + env_value("S3_ACCESS_KEY"),
              "-e", "AWS_SECRET_ACCESS_KEY=" + env_value("S3_SECRET_KEY"),
              "-e", "AWS_DEFAULT_REGION=us-east-1",
              AWS_IMAGE, "--endpoint-url", "http://s3:9000", "s3api", *args,
              "--bucket", "repsy", "--output", "json")


def stored_objects(backend):
    """{'<type>/<repo id>/<path>': size}, without the trash, plus the leftover count."""
    objects = {}
    if backend == "fs":
        out = sh("docker", "compose", "exec", "-T", "repsy", "sh", "-c",
                 "cd " + os.environ.get("CHECK_STORAGE_DIR", "/app/data/storage") + " && find . -type f -exec stat -c '%s %n' {} +")
        leftovers = 0
        for line in out.splitlines():
            size, path = line.split(" ", 1)
            path = path[2:]
            if re.match(r"^[a-z]+/trash/", path):
                continue
            if re.search(r"/\.repsy-write-[^/]+\.tmp$", path):
                leftovers += 1
                continue
            objects[path] = int(size)
        return objects, leftovers
    listing = json.loads(aws("list-objects-v2") or "{}")
    for obj in listing.get("Contents", []):
        key = obj["Key"]
        if key.startswith("trash/") or key.endswith("/"):
            continue
        objects[key] = int(obj["Size"])
    uploads = json.loads(aws("list-multipart-uploads") or "{}")
    return objects, len(uploads.get("Uploads", []))


def main():
    backend = sys.argv[1]
    objects, leftovers = stored_objects(backend)
    repos = {name: (rid, int(usage)) for name, rid, usage in
             psql("select name, id, disk_usage from repo where name in ('maven','npm')")}

    maven_id = repos["maven"][0]
    poms, jars = set(), set()
    for path in objects:
        if not path.startswith(f"maven/{maven_id}/"):
            continue
        parts = path.split("/")[2:]
        if len(parts) < 4:
            continue
        *group, artifact, version, name = parts
        gav = (".".join(group), artifact, version)
        if name == f"{artifact}-{version}.pom":
            poms.add(gav)
        elif name.endswith(".jar"):
            jars.add(gav)
    rows = {tuple(r) for r in psql(
        "select a.group_name, a.artifact_name, v.version_name from maven_artifact_version v "
        f"join maven_artifact a on a.id = v.artifact_id where a.repo_id = '{maven_id}'")}

    npm_id = repos["npm"][0]
    tgzs = set()
    for path in objects:
        m = re.match(rf"^npm/{npm_id}/([^@/][^/]*)/\1-(.+)\.tgz$", path)
        if m:
            tgzs.add((m.group(1), m.group(2)))
    npm_rows = {tuple(r) for r in psql(
        "select p.name, v.version from npm_package_version v join npm_package p "
        f"on p.id = v.package_id where p.repo_id = '{npm_id}' and p.scope is null")}

    def stored_bytes(repo_type, repo_id):
        return sum(s for p, s in objects.items() if p.startswith(f"{repo_type}/{repo_id}/"))

    result = {
        "backend": backend,
        "objects": len(objects),
        "maven": {
            "versions": len(rows),
            "pom_without_row": sorted("/".join(g) for g in poms - rows),
            "row_without_pom": sorted("/".join(g) for g in rows - poms),
            "jar_without_pom": sorted("/".join(g) for g in jars - poms),
        },
        "npm": {
            "versions": len(npm_rows),
            "tgz_without_row": sorted("@".join(t) for t in tgzs - npm_rows),
            "row_without_tgz": sorted("@".join(t) for t in npm_rows - tgzs),
        },
        "usage_drift": {
            name: usage - stored_bytes(name, rid) for name, (rid, usage) in repos.items()
        },
        "leftovers": leftovers,
    }
    print(json.dumps(result, indent=1))


if __name__ == "__main__":
    main()
