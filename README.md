# mod-batch-print

Copyright (C) 2022-2023 The Open Library Foundation

This software is distributed under the terms of the Apache License,
Version 2.0. See the file "[LICENSE](LICENSE)" for more information.

## Introduction

mod-batch-print is a service to provide option to print notices in
daily batch approach.

It is currently implemented with PostgresQL as storage.


## Compilation

Requirements:

* Java 21 or later
* Maven 3.6.3 or later
* Docker (unless `-DskipTests` is used)

Note: Debian package maven-3.6.3-1
[does not work with Java16/Java17](https://bugs.launchpad.net/ubuntu/+source/maven/+bug/1930541)


You need `JAVA_HOME` set, e.g.:

   * Linux: `export JAVA_HOME=$(readlink -f /usr/bin/javac | sed "s:bin/javac::")`
   * macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`

Build all components with: `mvn install`

## Server

You will need Postgres 12 or later.

You can create an empty database and a user with, e.g:

```
CREATE DATABASE folio_modules;
CREATE USER folio WITH CREATEROLE PASSWORD 'folio';
GRANT ALL PRIVILEGES ON DATABASE folio_modules TO folio;
```

The module's database connection is then configured by setting environment
variables:
`DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `DB_DATABASE`,
`DB_MAXPOOLSIZE`.

Once configured, start the module with:

```
java -Dport=8081 -jar target/mod-batch-print-fat.jar
```

## Running with Docker

If you feel adventurous and want to run mod-batch-print in a docker container, build the container first:

```
docker build -t mod-batch-print:latest .
```

And run with the module port exposed (`8081` by default):

```
docker run -e DB_HOST=host.docker.internal \
  -e DB_USERNAME=folio \
  -e DB_PASSWORD=folio \
  -e DB_DATABASE=folio_modules \
  -p 8081:8081 --name batch-print mod-batch-print:latest
```

**Note**: The magic host `host.docker.internal` is required to access
the DB and may be only available in Docker Desktop.
If it's not defined you can specify it by passing
`--add-host=host.docker.internal:<docker bridge net IP>` to the run command.

**Note**: Those docker build and run commands do work as-is with
[Colima](https://github.com/abiosoft/colima).

## Additional information

### Issue tracker

See project [MODBATPRNT](https://issues.folio.org/browse/MODBATPRNT)
at the [FOLIO issue tracker](https://dev.folio.org/guidelines/issue-tracker).

### Code of Conduct

Refer to the Wiki
[FOLIO Code of Conduct](https://wiki.folio.org/display/COMMUNITY/FOLIO+Code+of+Conduct).

### ModuleDescriptor

See the [ModuleDescriptor](descriptors/ModuleDescriptor-template.json)
for the interfaces that this module requires and provides, the permissions,
and the additional module metadata.

### API documentation

API descriptions:

 * [OpenAPI](src/main/resources/openapi/batchPrint.yaml)
 * [Schemas](src/main/resources/openapi/schemas/)

Generated [API documentation](https://dev.folio.org/reference/api/#mod-batch-print).

### Code analysis

[SonarQube analysis](https://sonarcloud.io/dashboard?id=org.folio%3Amod-batch-print).

### Download and configuration

The built artifacts for this module are available.
See [configuration](https://dev.folio.org/download/artifacts) for repository access,
and the Docker images for [released versions](https://hub.docker.com/r/folioorg/mod-batch-print/)
and for [snapshot versions](https://hub.docker.com/r/folioci/mod-batch-print/).

