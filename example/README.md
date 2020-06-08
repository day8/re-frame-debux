# Re-frame Debux

Example app using re-frame/debux

## Getting Started

```
$ lein clean
$ lein dev
```

1. Browse to [http://localhost:8280/](http://localhost:8280/)
2. Open the re-frame 10x window (ctrl-h)
3. Go to the events tab in 10x and click on an example button to trigger a traced events

## Testing this project with checkouts

```
$ cd ..
$ lein install
```

1. Make note of the version that is installed
2. The checkout directory is already setup in the project.clj
3. Change the `[day8.re-frame/tracing "0.5.5"]` dependency to reflect the version you are working on.