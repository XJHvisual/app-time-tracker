# AppTimeTracker

A Java-based application usage time tracker that monitors and logs active application usage time.

## Features

- Monitors foreground application usage in real-time
- Stores usage logs in SQLite database
- PowerShell integration for Windows system calls

## Requirements

- Java 8+
- SQLite JDBC driver

## Usage

Compile:
`ash
javac AppTimeTracker.java
`

Run:
`java
java AppTimeTracker
`

Or use the packaged JAR in dist/:
`ash
java -jar dist/AppTimeTracker.jar
`

## License

MIT