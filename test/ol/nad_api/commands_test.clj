(ns ol.nad-api.commands-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ol.nad-api.commands :as sut]))

(deftest commands-registry-test
  (testing "commands map exists and contains expected Main domain keys"
    (is (map? sut/commands))
    (is (contains? sut/commands "Main.Power"))
    (is (contains? sut/commands "Main.Volume"))
    (is (contains? sut/commands "Main.Source"))
    (is (contains? sut/commands "Main.Mute"))
    (is (contains? sut/commands "Main.Model"))
    (is (contains? sut/commands "Main.Version")))

  (testing "commands map contains Zone2 domain keys"
    (is (contains? sut/commands "Zone2.Power"))
    (is (contains? sut/commands "Zone2.Volume"))
    (is (contains? sut/commands "Zone2.Source"))
    (is (contains? sut/commands "Zone2.Mute")))

  (testing "each command has required keys"
    (doseq [[cmd cmd-def] sut/commands]
      (is (string? cmd)
          (str cmd " key should be a string"))
      (is (set? (:operators cmd-def))
          (str cmd " should have :operators set")))))

(deftest valid-operator-test
  (testing "validates operators for main commands"
    (is (true? (sut/valid-operator? "Main.Power" "?")))
    (is (true? (sut/valid-operator? "Main.Power" "=")))
    (is (true? (sut/valid-operator? "Main.Power" "+")))
    (is (true? (sut/valid-operator? "Main.Power" "-")))
    (is (false? (sut/valid-operator? "Main.Power" "!"))))

  (testing "model and version only support query operator"
    (is (true? (sut/valid-operator? "Main.Model" "?")))
    (is (false? (sut/valid-operator? "Main.Model" "=")))
    (is (true? (sut/valid-operator? "Main.Version" "?")))
    (is (false? (sut/valid-operator? "Main.Version" "="))))

  (testing "zone2 commands support standard operators"
    (is (true? (sut/valid-operator? "Zone2.Power" "?")))
    (is (true? (sut/valid-operator? "Zone2.Power" "=")))
    (is (true? (sut/valid-operator? "Zone2.Volume" "+")))
    (is (true? (sut/valid-operator? "Zone2.Volume" "-"))))

  (testing "returns false for unknown commands"
    (is (false? (sut/valid-operator? "Unknown.Command" "?")))))

(deftest build-command-test
  (testing "builds query commands"
    (is (= "Main.Power?" (sut/build-command "Main.Power" "?" nil)))
    (is (= "Main.Volume?" (sut/build-command "Main.Volume" "?" nil)))
    (is (= "Zone2.Power?" (sut/build-command "Zone2.Power" "?" nil))))

  (testing "builds set commands with values"
    (is (= "Main.Power=On" (sut/build-command "Main.Power" "=" "On")))
    (is (= "Main.Power=Off" (sut/build-command "Main.Power" "=" "Off")))
    (is (= "Main.Volume=-48" (sut/build-command "Main.Volume" "=" "-48")))
    (is (= "Main.Source=6" (sut/build-command "Main.Source" "=" "6")))
    (is (= "Zone2.Power=On" (sut/build-command "Zone2.Power" "=" "On"))))

  (testing "builds increment/decrement commands"
    (is (= "Main.Volume+" (sut/build-command "Main.Volume" "+" nil)))
    (is (= "Main.Volume-" (sut/build-command "Main.Volume" "-" nil)))
    (is (= "Zone2.Volume+" (sut/build-command "Zone2.Volume" "+" nil)))))
