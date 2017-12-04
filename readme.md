# Otoroshi with Akka-Http


## In dev mode

```
sbt ~re-start
```

## Build for prod

```
sbt ';clean;compile;assembly'
```

## Run for prod

```
java -jar ./target/scala-2.12/otoroshi.jar
```

## Todo

* [ ] add implicit class for HttpRequest to get host, uriString, domain, etc ...
* @inline stuff