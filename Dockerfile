FROM ubuntu:24.04

# Install Java 26
RUN apt-get update && apt-get install -y wget tar && \
    wget -q https://download.oracle.com/java/26/latest/jdk-26_linux-x64_bin.tar.gz && \
    tar -xzf jdk-26_linux-x64_bin.tar.gz -C /opt/ && \
    rm jdk-26_linux-x64_bin.tar.gz

ENV JAVA_HOME=/opt/jdk-26.0.1
ENV PATH=$JAVA_HOME/bin:$PATH

WORKDIR /app

# Copy files
COPY Server.java .
COPY index.html .

# Create required folders
RUN mkdir -p data players

# Compile
RUN javac Server.java

EXPOSE 8080

CMD ["java", "-Dfile.encoding=UTF-8", "Server"]
