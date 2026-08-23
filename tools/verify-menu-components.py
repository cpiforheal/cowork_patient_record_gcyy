# -*- coding: utf-8 -*-
"""校验后端菜单组件串与前端视图文件一一对应，防止"动态路由组件不存在"白屏回归。

用法（仓库根目录执行）：
    python tools/verify-menu-components.py

规则：AuthNavigationService.buildMenus() 中所有 page()/pageWithActiveMenu() 的
组件串（第三个参数，如 /preAi/encounters/index）必须在
coshare_patientrecord_sys_frontend/Geeker-Admin/src/views/ 下存在对应的
<component>.vue 文件——与前端 dynamicRouter.resolveViewComponent 的精确路径
解析逻辑保持一致（无别名、无大小写转换）。

退出码：0=全部可解析；1=存在缺失（构建前必须修复）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVICE = os.path.join(
    ROOT,
    "coshare_patientrecord_sys_backend",
    "src", "main", "java", "com", "coshare", "patientrecord", "auth", "service",
    "AuthNavigationService.java",
)
VIEWS = os.path.join(
    ROOT,
    "coshare_patientrecord_sys_frontend", "Geeker-Admin", "src", "views",
)

PAGE_PATTERN = re.compile(
    r'page(?:WithActiveMenu)?\(\s*"[^"]+"\s*,\s*"[^"]+"\s*,\s*"(/[^"]+)"'
)


def main() -> int:
    with open(SERVICE, encoding="utf-8") as handle:
        source = handle.read()
    components = sorted(set(PAGE_PATTERN.findall(source)))
    missing = [
        component
        for component in components
        if not os.path.isfile(os.path.join(VIEWS, component.lstrip("/") + ".vue"))
    ]
    print(f"菜单组件串 {len(components)} 个")
    for component in components:
        mark = "MISSING" if component in missing else "ok"
        print(f"  [{mark}] {component}")
    if missing:
        print(f"\n失败：{len(missing)} 个组件串无对应视图文件（前端将抛\"动态路由组件不存在\"）：")
        for component in missing:
            print(f"  - {component}")
        return 1
    print("\n全部组件串可解析。")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
