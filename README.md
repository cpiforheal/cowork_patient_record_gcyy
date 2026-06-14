# cowork_patient_record_gcyy

协作病历资料原型工作区。`Geeker-Admin/` 已作为本仓库内的 Vue 3 前端工程纳入版本管理，不再是嵌套 Git 仓库或外部参考工程；业务前端改动直接提交到当前仓库。

## 本地数据服务

~~~bash
pnpm api
~~~

默认启动 http://localhost:7071，提供 /clinic-api/db、/clinic-api/schema、/clinic-api/reset 等接口。

首次读取会从 server/data/clinic-db.seed.json 生成本地运行数据 server/data/clinic-db.json；运行数据已加入 .gitignore，避免把本机调试状态提交进仓库。服务端会在读取和重置时补齐角色、字段规则等系统 schema，确保空业务数据也能支撑权限配置页面。

## 重置演示数据

~~~bash
pnpm api:reset
~~~

接口契约见 docs/clinic-data-contract.md。
