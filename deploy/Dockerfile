FROM eclipse-temurin:8-jre

# 全系统时间口径为 PHT。基础镜像默认 UTC，不设这两处会让容器日志时间戳与业务时间差 8 小时。
ENV TZ=Asia/Manila
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /opt/app
COPY collection-admin/target/collection-admin.jar app.jar

# Valubo 测试 Facade 为自签名（CN=valubo-voice-test）。只导入这一张，不关全局 TLS 校验。
# 构建上下文需包含 deploy/certs/valubo-voice-test.crt（aicall-e2e 目录同样放置该相对路径）。
COPY deploy/certs/valubo-voice-test.crt /tmp/valubo-voice-test.crt
RUN keytool -importcert -noprompt -alias valubo-voice-test \
      -file /tmp/valubo-voice-test.crt \
      -keystore "$JAVA_HOME/lib/security/cacerts" \
      -storepass changeit \
    && rm -f /tmp/valubo-voice-test.crt

EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Manila", "-jar", "/opt/app/app.jar"]
