[![Gitter chat](https://badges.gitter.im/emc-mongoose.png)](https://gitter.im/emc-mongoose)
[![Issue Tracker](https://img.shields.io/badge/Issue-Tracker-orange.svg)](https://mongoose-issues.atlassian.net/jira/software/c/projects/KVS/issues?filter=allissues)
[![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-pravega/maven-metadata.xml.svg)](http://central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-pravega-kvs)
[![Docker Pulls](https://img.shields.io/docker/pulls/emcmongoose/mongoose-storage-driver-pravega-kvs.svg)](https://hub.docker.com/r/emcmongoose/mongoose-storage-driver-pravega-kvs/)

# Content

1. [Introduction](#1-introduction)<br/>
2. [Features](#2-features)<br/>
3. [Deployment](#3-deployment)<br/>
&nbsp;&nbsp;3.1. [Basic](#31-basic)<br/>
&nbsp;&nbsp;3.2. [Docker](#32-docker)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;3.2.1. [Standalone](#321-standalone)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;3.2.2. [Distributed](#322-distributed)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;3.2.2.1. [Additional Node](#3221-additional-node)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;3.2.2.2. [Entry Node](#3222-entry-node)<br/>
4. [Configuration](#4-configuration)<br/>
&nbsp;&nbsp;4.1. [Specific Options](#41-specific-options)<br/>
&nbsp;&nbsp;4.2. [Tuning](#42-tuning)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;4.2.1. [Concurrency](#421-concurrency)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;4.2.2. [Base Storage Driver Usage Warnings](#422-base-storage-driver-usage-warnings)<br/>
5. [Usage](#5-usage)<br/>
TBD
6. [Development](#7-development)<br/>
&nbsp;&nbsp;7.1. [Build](#71-build)<br/>
&nbsp;&nbsp;7.2. [Test](#72-test)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;6.2.1. [Automated](#721-automated)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;6.2.1.1. [Unit](#7211-unit)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;6.2.1.2. [Integration](#7212-integration)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;6.2.1.3. [Functional](#7213-functional)<br/>
&nbsp;&nbsp;&nbsp;&nbsp;6.2.2. [Manual](#722-manual)<br/>

# 1. Introduction

This driver is intended to estimate performance of Pravega Key-Value Store. To work with streams refer to the 
[mongoose-storage-driver-pravega](https://github.com/emc-mongoose/mongoose-storage-driver-pravega)

Mongoose and Pravega are using quite different concepts. So it's necessary to determine how
[Pravega KVS-specific terms](http://pravega.io/docs/latest/terminology/) are mapped to the
[Mongoose abstractions]((https://gitlab.com/emcmongoose/mongoose/tree/master/doc/design/architecture#1-basic-terms)).

| Pravega | Mongoose |
|---------|----------|
| [Key-Value Table](https://github.com/pravega/pravega/wiki/PDP-39-Key-Value-Tables) | *Item Path* or *Data Item* |
| Scope | Storage Namespace
| [Key-Value Pair](http://pravega.io/docs/latest/pravega-concepts/) | *Data Item* |
| Table Segment (KVT Partition) | N/A |

# 2. Features

TBD

# 3. Deployment

## 3.1. Basic

Java 11+ is required to build/run.

1. Get the latest `mongoose-base` jar from the 
[maven repo](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-base/)
and put it to your working directory. Note the particular version, which is referred as *BASE_VERSION* below.

2. Get the latest `mongoose-storage-driver-coop` jar from the
[maven repo](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-coop/)
and put it to the `~/.mongoose/<BASE_VERSION>/ext` directory.

3. Get the latest `mongoose-storage-driver-pravega-kvs` jar from the
[maven repo](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-pravega-kvs/)
and put it to the `~/.mongoose/<BASE_VERSION>/ext` directory.

```bash
java -jar mongoose-base-<BASE_VERSION>.jar \
    --storage-driver-type=pravega-kvs \
    --storage-namespace=scope1 \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-net-node-port=9090 \
    --load-batch-size=100 \
    --storage-driver-limit-queue-input=10000 \
    ...
```

## 3.2. Docker

### 3.2.1. Standalone

```bash
docker run \
    --network host \
    emcmongoose/mongoose-storage-driver-pravega-kvs \
    --storage-namespace=scope1 \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --load-batch-size=100 \
    --storage-driver-limit-queue-input=10000 \
    ...
```

### 3.2.2. Distributed

#### 3.2.2.1. Additional Node

```bash
docker run \
    --network host \
    --expose 1099 \
    emcmongoose/mongoose-storage-driver-pravega-kvs \
    --run-node
```

#### 3.2.2.2. Entry Node

```bash
docker run \
    --network host \
    emcmongoose/mongoose-storage-driver-pravega-kvs \
    --load-step-node-addrs=<ADDR1,ADDR2,...> \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-namespace=scope1 \
    --load-batch-size=100 \
    --storage-driver-limit-queue-input=10000 \
    ...
```

# 4. Configuration

## 4.1. Specific Options

| Name                               | Type            | Default Value | Description                                      |
|:-----------------------------------|:----------------|:--------------|:-------------------------------------------------|
| storage-driver-control-scope       | boolean         | true          | Allow to try to create scope
| storage-driver-control-timeoutMillis | integer       | 2000         | The timeout for any Pravega Controller API call
| storage-driver-event-key-enabled   | boolean         | false         | Specifies if Mongoose should generate its own routing key during the events creation
| storage-driver-event-key-count     | integer         | 0             | Specifies a max count of unique routing keys to use during the events creation (may be considered as a routing key period). 0 value means to use unique routing key for each new event
| storage-net-node-addrs             | list of strings | 127.0.0.1     | The list of the Pravega storage nodes to use for the load
| storage-net-node-port              | integer         | 9090          | The default port of the Pravega storage nodes, should be explicitly set to 9090 (the value used by Pravega by default)
| storage-net-maxConnPerSegmentstore | integer         | 5             | The default amount of connections per each Pravega Segmentstore

## 4.2. Tuning

* `storage-net-maxConnPerSegmentstore`
This parameter can largely affect the performance, but it also increases network workload

### 4.2.2. Base Storage Driver Usage Warnings

See the [design notes](https://github.com/emc-mongoose/mongoose-storage-driver-coop#design)

# 5. Usage
TBD


# 6. Open Issues

| Issue | Description |
|-------|-------------|

# 7. Development

## 7.1. Build

Note the Pravega commit # which should be used to build the corresponding Mongoose plugin.
Specify the required Pravega commit # in the `build.gradle` file. Then run:

```bash
./gradlew clean jar
```

## 7.2. Test

### 7.2.1. Automated

#### 7.2.1.1. Unit

```bash
./gradlew clean test
```

#### 7.2.1.2. Integration
```bash
docker run -d --name=storage --network=host pravega/pravega:<PRAVEGA_VERSION> standalone
./gradlew integrationTest
```

#### 7.2.1.3. Functional
TBD

### 7.2.1. Manual

1. [Build the storage driver](#71-build)
2. Copy the storage driver's jar file into the mongoose's `ext` directory:
```bash
cp -f build/libs/mongoose-storage-driver-pravega-kvs-*.jar ~/.mongoose/<MONGOOSE_BASE_VERSION>/ext/
```
Note that the Pravega storage driver depends on the 
[Coop Storage Driver](http://repo.maven.apache.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-coop/)
extension so it should be also put into the `ext` directory
3. Build and install the corresponding Pravega version:
```bash
./gradlew pravegaExtract
```
4. Run the Pravega standalone node:
```bash
build/pravega_/bin/pravega-standalone
```
4. Run Mongoose's default scenario with some specific command-line arguments:
```bash
java -jar mongoose-<MONGOOSE_BASE_VERSION>.jar \
    --storage-driver-type=pravega-kvs \
    --storage-net-node-port=9090 \
    --storage-driver-limit-concurrency=10 \
    --item-output-path=goose-events-stream-0
```
