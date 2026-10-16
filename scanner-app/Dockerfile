# Build the JAR, including the HTML/CSS/JavaScript frontend.
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package

# Ubuntu 24.04 supplies Python 3.12 for the existing OCR dependencies.
FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       python3 python3-venv libzbar0 libgl1 libglib2.0-0t64 libgomp1 \
       ca-certificates fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

ENV SCANNER_PYTHON=/opt/venv/bin/python \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HOME=/tmp

RUN python3 -m venv /opt/venv
COPY requirements-ocr.txt /app/requirements-ocr.txt
RUN /opt/venv/bin/python -m pip install --no-cache-dir -r /app/requirements-ocr.txt \
    && /opt/venv/bin/python -m pip check

WORKDIR /app
COPY ocr.py ./ocr.py
COPY --from=build /build/target/scanner-app-1.0.0.jar ./scanner-app.jar

# Detect missing native libraries/models during the build, before deployment.
RUN /opt/venv/bin/python -c "import ocr; assert ocr.HAS_PYZBAR; assert ocr.HAS_CV2; ocr.initialize_ocr(); assert ocr.OCR_BACKEND == 'rapid'" \
    && mkdir -p /data/images /data/reports

USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/app/scanner-app.jar"]
