#!/usr/bin/env python3
import zipfile
z = zipfile.ZipFile("/opt/app/build/collection-admin/target/collection-admin.jar")
libs = [n for n in z.namelist() if n.endswith("collection-engine-1.0.0-SNAPSHOT.jar") or n.endswith("collection-channel-1.0.0-SNAPSHOT.jar")]
print("libs", libs)
import io
for n in libs:
    inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
    hits = 0
    for cn in inner.namelist():
        if cn.endswith(".class"):
            hits += inner.read(cn).count(b"CONNECT_AND_STOP")
    print(n, "CONNECT_AND_STOP", hits)
