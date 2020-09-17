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
&nbsp;&nbsp;5.1. [Create](#51-create)<br/>
&nbsp;&nbsp;5.2. [Read](#52-read)<br/>
&nbsp;&nbsp;5.3. [Update](#53-update)<br/>
&nbsp;&nbsp;5.4. [Delete](#54-delete)<br/>
&nbsp;&nbsp;5.5. [Key families](#55-key-families)<br/>
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
    ...
```

# 4. Configuration

## 4.1. Specific Options

| Name                                 | Type            | Default Value | Description                                      |
|:-------------------------------------|:----------------|:--------------|:-------------------------------------------------|
| storage-driver-control-scope         | boolean         | true          | Allow to try to create scope
| storage-driver-control-kvt           | boolean         | true          | Allow to try to create kvt
| storage-driver-control-timeoutMillis | integer         | 5000          | The timeout for any Pravega Controller API call
| storage-driver-event-key-enabled     | boolean         | false         | Specifies if Mongoose should generate its own routing key during the events creation
| storage-driver-event-key-count       | integer         | 0             | Specifies a max count of unique routing keys to use during the events creation (may be considered as a routing key period). 0 value means to use unique routing key for each new event
| storage-net-node-addrs               | list of strings | 127.0.0.1     | The list of the Pravega storage nodes to use for the load
| storage-net-node-port                | integer         | 9090          | The default port of the Pravega storage nodes, should be explicitly set to 9090 (the value used by Pravega by default)
| storage-net-maxConnPerSegmentstore   | integer         | 5             | The default amount of connections per each Pravega Segmentstore
| storage-driver-family-key-enabled    | boolean         | false         | Specifies if Mongoose should use Key Families
| storage-driver-family-key-count      | long            | 0             | The default amount of Key Families
| storage-driver-family-key-allow-empty| boolean         | false         | Specifies if Mongoose should allow KVP w/o Key Families
| storage-driver-scaling-partitions    | int             | 1             | The default amount of partitions (Table segments) in KVT


## 4.2. Tuning

* `storage-net-maxConnPerSegmentstore`
This parameter can largely affect the performance, but it also increases network workload

### 4.2.2. Base Storage Driver Usage Warnings

See the [design notes](https://github.com/emc-mongoose/mongoose-storage-driver-coop#design)

# 5. Usage

## 5.1 Create

```bash
java -jar mongoose-base-<BASE_VERSION>.jar \
    --storage-driver-type=pravega-kvs \
    --storage-namespace=scope1 \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-net-node-port=9090 \
    --item-output-path=items.csv \
    ...
```

## 5.2 Read

Right now only the read from file is supported (so the option `--item-output-path=items.csv` is used in create).

```bash
java -jar mongoose-base-<BASE_VERSION>.jar \
    --load-op-type=read \
    --storage-driver-type=pravega-kvs \
    --storage-namespace=scope1 \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-net-node-port=9090 \
    --item-input-file=items.csv \
    ...
```

## 5.3 Update

To run an update load mongoose needs to know the keys to update which so far can only be provided by specifying 
`--item-input-path=items.csv` option. To create the file use `--item-output-path=items.csv` in create mode. 
As mongoose uses a fixed seed you need to alter the seed to upload different data. To have a convenient way of setting 
a new seed for each run learn more about [expression language](https://github.com/emc-mongoose/mongoose-base/blob/master/src/main/java/com/emc/mongoose/base/config/el/README.md).

One thing to notice: Pravega uses same mechanism for creates and updates. So if you update non-existing keys you basically
create them. There is no way you can pass a key to update and get 404. You should use read mode for that.

```bash
java -jar mongoose-base-<BASE_VERSION>.jar \
    --load-op-type=update \
    --storage-driver-type=pravega-kvs \
    --storage-namespace=scope1 \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-net-node-port=9090 \
    --item-input-file=items.csv \
    --item-data-input-seed=7a42d9c482144167 \
    ...
```

## 5.4 Delete 

To run a delete load mongoose needs to know the keys to delete which so far can only be provided by specifying 
`--item-input-path=items.csv` option. To create the file use `--item-output-path=items.csv` in create mode. 
One thing to notice: Pravega checks the key sent in the request, if the key exists, Pravega deletes it. If it doesn't, 
Pravega still says everything's fine, so Mongoose understands that as a successful operation. This way you can delete 
same N keys an endless amount of times and each time get N successfully finished requests, though the keys were actually only
deleted the first time.

```bash
java -jar mongoose-base-<BASE_VERSION>.jar \
    --load-op-type=delete \
    --storage-driver-type=pravega-kvs \
    --storage-namespace=scope1 \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-net-node-port=9090 \
    --item-input-file=items.csv \
    ...
```

## 5.5 Key families

Key families are disabled by default. 

To do creates with Key families one needs to enable it and set the amount of keys (`family-key` parameters). 
If also having an empty family during creates is desired, then allow-empty flag can be used. 

Reads do not require any additional flags for key families as long as the input-file is used.

So, a full example with 10 key families and allowed no key family looks like this:

```bash
java -jar mongoose-base-<BASE_VERSION>.jar \
    --storage-driver-type=pravega-kvs \
    --storage-namespace=scope1 \
    --storage-net-node-addrs=<NODE_IP_ADDRS> \
    --storage-net-node-port=9090 \
    --storage-driver-family-key-enabled \
    --storage-driver-family-key-count=10 \
    --storage-driver-family-key-allow-empty
    ...
```
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
./gradlew pravegaDistInstall
```
4. Run the Pravega standalone node:
```bash
cd build/pravega/build/distributions/
tar -xzf pravega-<version>.tgz
./pravega-<version>/bin/pravega-standalone
```
4. Run Mongoose's default scenario with some specific command-line arguments:
```bash
java -jar mongoose-<MONGOOSE_BASE_VERSION>.jar \
    --storage-driver-type=pravega-kvs \
    --storage-net-node-port=9090 \
    --storage-driver-limit-concurrency=10 \
    --item-output-path=goose-events-stream-0
```
