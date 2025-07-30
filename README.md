# FASR: Formalizing and Automating STPA with Robustness

This repository holds code to support the partial automation of the [System Theoretic Process Analysis](http://psas.scripts.mit.edu/home/get_file.php?name=STPA_Handbook.pdf "STPA Handbook (PDF)").

## Building

### The STPA Guideword Classifier

This compares two traces through a system -- one safe and one unsafe -- and creates an unsafe control action for the unsafe trace. [Fortis](https://github.com/cmu-soda/fortis-core) can be used to generate the traces from a [TLA+](https://lamport.azurewebsites.net/tla/tla.html) or [FSP](https://www.doc.ic.ac.uk/~jnm/LTSdocumention/FSP-notation.html) model of a system and its environment.

#### The classifier code

After cloning the repository, use maven to build the project:

```
% pwd
/Users/sprocter/git/fasr/UCAClassification_EditDistance
% mvn clean verify
[INFO] Scanning for projects...
[INFO]
[INFO] -----------------------------< FASR:FASR >------------------------------
[INFO] Building Formalization and Automation of STPA using Robustness 0.0.1-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- clean:3.2.0:clean (default-clean) @ FASR ---
[...]
```

#### Fortis

You will also need the [FASR branch of Fortis](https://github.com/cmu-soda/fortis-core/tree/FASR). After cloning and switching to the FASR branch, make sure you can run the "Water Tank" example:

```json
% pwd
/Users/sprocter/git/fortis-core/examples/water_tank
% java -jar ../../out/artifacts/fortis_core_jar/fortis-core.jar robustness --stpa --tla-sys WaterTank.tla --cfg-sys WaterTank.cfg --tla-env WaterTankEnv.tla --cfg-env WaterTankEnv.cfg | tail -2 | head -1 | jq

[
  {
    "goodTrace": [
      "TurnPumpOn",
      "Wait",
      "Wait",
      "TurnPumpOff"
    ],
    "badTrace": [
      "TurnPumpOn",
      "Wait",
      "Wait",
      "Wait"
    ]
  },
  [...]
]
```

#### Classifying Fortis Output

You can now pipe the output from Fortis (using the `--stpa` flag) to the UCA Classifier:

```json
% pwd
% java -jar ../../out/artifacts/fortis_core_jar/fortis-core.jar robustness --stpa --tla-sys WaterTank.tla --cfg-sys WaterTank.cfg --tla-env WaterTankEnv.tla --cfg-env WaterTankEnv.cfg | java -jar ~/git/fasr/UCAClassification_EditDistance/target/FASR-0.0.1-SNAPSHOT-jar-with-dependencies.jar | jq

[
  {
    "source": "##PLACEHOLDER##",
    "guideword": "NotProviding",
    "controlAction": "TurnPumpOff",
    "context": [
      "TurnPumpOn",
      "Wait",
      "Wait"
    ],
    [...]
]