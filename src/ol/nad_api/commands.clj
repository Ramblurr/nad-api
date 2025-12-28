(ns ol.nad-api.commands
  "NAD receiver command definitions as data.

  Commands are keyed by their wire format string (e.g., `\"Main.Power\"`, `\"Zone2.Volume\"`).
  Each command has:
  - `:operators` - Set of valid operators as strings (\"?\", \"=\", \"+\", \"-\")
  - `:description` - Human-readable description of the command
  - `:example` - Example usage from the protocol spec
  - `:values` - Optional: possible values or range specification")

(set! *warn-on-reflection* true)

(def ^{:doc "Registry of NAD receiver commands.

  Structure:
  ```clojure
  {\"Main.Power\" {:operators #{\"?\" \"=\" \"+\" \"-\"}
                 :description \"Turn the Main Power On/Off\"
                 :example \"Main.Power=On\"
                 :values #{\"On\" \"Off\"}}
   ...}
  ```"}
  commands
  {;; Main domain - core controls
   "Main.Power"   {:operators   #{"?" "=" "+" "-"}
                   :description "Turn the Main Power On/Off"
                   :example     "Main.Power=On"
                   :values      #{"On" "Off"}}

   "Main.Volume"  {:operators   #{"?" "=" "+" "-"}
                   :description "Set Main Volume (range depends on levels, trims, etc)"
                   :example     "Main.Volume=-48"
                   :values      {:type :range :min -99 :max 19}}

   "Main.Source"  {:operators   #{"?" "=" "+" "-"}
                   :description "Set Main Source"
                   :example     "Main.Source=1"
                   :values      {:type :range :min 1 :max 10}}

   "Main.Mute"    {:operators   #{"?" "=" "+" "-"}
                   :description "Set Mute"
                   :example     "Main.Mute=On"
                   :values      #{"On" "Off"}}

   "Main.Model"   {:operators   #{"?"}
                   :description "Query AVR Model"
                   :example     "Main.Model?"
                   :values      nil}

   "Main.Version" {:operators   #{"?"}
                   :description "Query Main MCU Version"
                   :example     "Main.Version?"
                   :values      nil}

   ;; Zone2 domain
   "Zone2.Power"  {:operators   #{"?" "=" "+" "-"}
                   :description "Set Zone 2 Power"
                   :example     "Zone2.Power=On"
                   :values      #{"On" "Off"}}

   "Zone2.Volume" {:operators   #{"?" "=" "+" "-"}
                   :description "Set Zone 2 Volume"
                   :example     "Zone2.Volume=-48"
                   :values      {:type :range :min -99 :max 19}}

   "Zone2.Source" {:operators   #{"?" "=" "+" "-"}
                   :description "Set Zone 2 Source"
                   :example     "Zone2.Source=1"
                   :values      {:type :range :min 1 :max 11}}

   "Zone2.Mute"   {:operators   #{"?" "=" "+" "-"}
                   :description "Set Zone 2 Mute"
                   :example     "Zone2.Mute=On"
                   :values      #{"On" "Off"}}})

(defn valid-operator?
  "Returns true if `operator` is valid for the given `cmd`.

  ```clojure
  (valid-operator? \"Main.Power\" \"?\") ;=> true
  (valid-operator? \"Main.Model\" \"=\") ;=> false
  ```"
  [cmd operator]
  (boolean (some-> (get-in commands [cmd :operators])
                   (contains? operator))))

(defn build-command
  "Builds the full command string (without line endings).

  ```clojure
  (build-command \"Main.Power\" \"?\" nil)    ;=> \"Main.Power?\"
  (build-command \"Main.Power\" \"=\" \"On\") ;=> \"Main.Power=On\"
  (build-command \"Main.Volume\" \"+\" nil)   ;=> \"Main.Volume+\"
  ```"
  [cmd operator value]
  (str cmd operator (or value "")))
