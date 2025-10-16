# FASR: Formalizing and Automating STPA with Robustness

This repository holds code to support the partial automation of the [System Theoretic Process Analysis](http://psas.scripts.mit.edu/home/get_file.php?name=STPA_Handbook.pdf "STPA Handbook (PDF)").

## Building

### Setting Up Eclipse Workspace

1. Open Eclipse Installer and install 2025-06
2. Open your workspace
3. Right click in the Package Explorer and select Import
4. Select Git > Projects from Git > GitHub
5. Search "cmu-sei/FASR" and select it
6. Right click on UCA_Classification and select Import > Install > Install Software Items from File
7. Select browse and navigate to `SoftwareRequirements.p2f` in the root directory of the cmu-sei/FASR clone.
8. Select all software that is not already installed, uncheck "Install latest version of selected software"
9.  Restart Eclipse

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

You will also need the [FASR branch of Fortis](https://github.com/cmu-soda/fortis-core/tree/FASR). After cloning and switching to the FASR branch, make sure you can run the "Water Tank" example using the `--stpa` flag:

```
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

You can now pipe the output from Fortis to the UCA Classifier:

```
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
```

### Running SysML Generator

#### Requirements
1. Cameo Enterprise Architecture
   1. STPA Library
   2. STPA Profile
   3. FASR Profile
2. An exported Cameo Enterprise Architecture Project
   1. Export To.. > Eclipse UML2 XMI File > Eclipse UML2 (v5.x) XMI File
#### Usage
1. Import project files into Eclipse
2. Pass the path to `<project_name>.uml` into a TraverseModel object 
3. Pass the TraverseModel object and the return from UCA_Classification into a SysMLGenerator object
4. Use TraverseModel's `exportModel()` to create a new `.uml` file or use `updateModel()` to update the `.uml` file that was passed in
5. If using `updateModel()`, then you'll need to close your project in Cameo and reopen it for the generated elements to load
