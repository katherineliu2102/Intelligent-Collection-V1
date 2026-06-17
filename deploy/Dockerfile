FROM eclipse-temurin:8-jre

WORKDIR /opt/app
COPY collection-admin/target/collection-admin.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]
