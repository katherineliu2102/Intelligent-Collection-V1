FROM eclipse-temurin:8-jre

# 全系统时间口径为 PHT。基础镜像默认 UTC，不设这两处会让容器日志时间戳与业务时间差 8 小时。
# JVM 侧 CollectionApplication.main 里还会再 setDefault 一次兜底（jar 被别的方式拉起时仍生效）。
ENV TZ=Asia/Manila
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /opt/app
COPY collection-admin/target/collection-admin.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Manila", "-jar", "/opt/app/app.jar"]
