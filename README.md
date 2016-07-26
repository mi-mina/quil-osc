# quil-osc

patch version of quil https://github.com/quil/quil to work with oscp5

## Changes made

### In [applet.clj](https://github.com/mi-mina/quil-osc/blob/master/src/clj/quil/applet.clj)

#### Added

```
(defn -oscEvent
  ([this message]
   (println "osc message received! Message: " message "   addrpattern:" (.addrPattern message) "   typetag:" (.typetag message))
   (with-applet this
     ((:osc-event (.state this)) message))))

(gen-interface
 :name quil.OscP5
 :methods [[oscEvent [oscP5.OscMessage] Object]])
```
#### Changed
- `gen-class`to implement `quil.OscP5`

- added `osc-event-fn`in `applet`

### In [bind_output.clj](https://github.com/mi-mina/quil-osc/blob/master/src/clj/quil/middlewares/bind_output.clj)
#### Changed

- `:osc-event` included as special case

### In [safe_fns.clj](https://github.com/mi-mina/quil-osc/blob/master/src/clj/quil/middlewares/safe_fns.clj)

#### Added
```
(defn- wrap-osc-event [function]
  (fn [message]
    (try
      (function message)
      (catch Exception e
        (println "Exception in :osc-event function:" e "\nstacktrace: " (with-out-str (print-cause-trace e)))
        (Thread/sleep 1000)))))
```
#### Changed
- `:osc-event` included as special case

### In [fun_mode.cljc](https://github.com/mi-mina/quil-osc/blob/master/src/cljc/quil/middlewares/fun_mode.cljc)

#### Added
```
(defn- wrap-osc-event [options]
  (if-let [handler (:osc-event options)]
    (assoc options :osc-event
           (fn [message]
             (swap! (q/state-atom) handler message)))
    options))
```
#### Changed
- Added `wrap-osc-event` handler to `fun-mode`



