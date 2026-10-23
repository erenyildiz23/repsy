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
"""Writes the JSON body of an `npm publish` of <name>@<version> with a tarball of <size> random
bytes inside package/ (what the npm client sends: PUT /<repo>/<name>).
Usage: python3 chaos/npm_payload.py <name> <version> <size bytes> > body.json"""
import base64
import hashlib
import io
import json
import os
import sys
import tarfile

name, version, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
manifest = {"name": name, "version": version, "description": "case study", "main": "index.js"}
buf = io.BytesIO()
with tarfile.open(fileobj=buf, mode="w:gz") as tar:
    for path, data in (("package/package.json", json.dumps(manifest).encode()),
                       ("package/blob.bin", os.urandom(size))):
        info = tarfile.TarInfo(path)
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
tgz = buf.getvalue()
filename = f"{name}-{version}.tgz"
body = {
    "_id": name,
    "name": name,
    "description": "case study",
    "dist-tags": {"latest": version},
    "versions": {version: {**manifest, "_id": f"{name}@{version}", "dist": {
        "shasum": hashlib.sha1(tgz).hexdigest(),
        "integrity": "sha512-" + base64.b64encode(hashlib.sha512(tgz).digest()).decode(),
        "tarball": f"http://localhost:9090/npm/{name}/-/{filename}"}}},
    "_attachments": {filename: {"content_type": "application/octet-stream",
                                "data": base64.b64encode(tgz).decode(), "length": len(tgz)}},
}
json.dump(body, sys.stdout)
