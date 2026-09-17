#!/usr/bin/env python3
import zipfile
z = zipfile.ZipFile("/opt/app/build/collection-admin/target/collection-admin.jar")
names = [n for n in z.namelist() if n.endswith("PlanLifecycleManager.class") or n.endswith("ConfigurableExecutionGuard.class") or n.endswith("DashboardController.class")]
print("classes")
for n in names:
    print(n)
data = b"".join(z.read(n) for n in names)
print("CONNECT_AND_STOP_count", data.count(b"CONNECT_AND_STOP"))
