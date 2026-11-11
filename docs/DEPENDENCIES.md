# 依赖锁定

初始化日期：2026-11-10。禁止版本区间和 SNAPSHOT。

| 组件 | 版本 | 来源 |
| --- | --- | --- |
| JDK | 17 | `maven.compiler.release` |
| Spring Boot | 3.5.16 | 父 POM，3.5 线最新稳定补丁 |
| Spring Security / JDBC | 跟随 Spring Boot 3.5.16 BOM | 父 POM |
| H2 | 2.3.232 | `mvn dependency:tree` 解析结果 |
| OSHI | 7.7.3 | 显式版本，JNA 实现，支持 JDK 17 |
| Vue | 3.5.45 | frontend/package.json |
| Vue Router | 5.4.0 | frontend/package.json |
| Ant Design Vue | 4.2.6 | frontend/package.json |
| ECharts | 6.1.0 | frontend/package.json |
| Vite | 8.3.7 | frontend/package.json |
| TypeScript | 5.9.3 | frontend/package.json |

H2 的解析版本在打包后由 `mvn -B dependency:list -DincludeScope=runtime` 写入发布包的 `DEPENDENCIES.txt`。2026-11-11 这次解析到 H2 2.3.232、JNA 5.19.1、Spring Framework 6.2.19、Spring Security 6.5.11、Tomcat 10.1.55。生产运行不下载 Maven 或 npm 依赖。
