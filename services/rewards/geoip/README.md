# GeoIP 数据库目录

请将从 MaxMind 官方账户下载、并符合相应授权条款的 `GeoLite2-Country.mmdb` 放在本目录：

```text
services/rewards/geoip/GeoLite2-Country.mmdb
```

该二进制数据库不会提交到 Git。仓库根目录 `.gitignore` 已忽略 `*.mmdb` 文件。

本地启动前，如果暂时不需要国家解析，可以不放置数据库；Rewards 服务仍可运行，只是 `geo_country` 为空。

Docker Compose 默认会将此文件只读挂载到容器：

```text
/usr/share/GeoIP/GeoLite2-Country.mmdb
```

生产部署时请在服务器本地准备文件，或通过 `GEOIP_DB_HOST_PATH` 指定一个本地路径。不要把 MaxMind 数据库上传到公开仓库。
