# Unified Service Scheduler – documentation pack

Keyloop technical assessment, Scenario A. Generated 25 October 2026 from build 0.2.0
(Scala 3.3.6 / Play 3.0.12 backend, React frontend).

| File | For | What it is |
| --- | --- | --- |
| `01-Technical-Document.pdf` | Reviewers, the team | Research on Keyloop and its dealerships, the design decisions and how they ended up in the build, concurrency and load results, the SOLID review |
| `02-Software-Requirements-Specification.pdf` | The client, future developers | Functional and non-functional requirements, business rules, API, data model, every diagram, technologies, installation guide |
| `03-Test-Specification.pdf` | Testers, the demo | 33 manual test cases for every feature and role, step by step, with a screenshot of each expected result |
| `diagrams/` | Everyone | All diagrams as PNG and SVG; `diagrams/source/` holds the PlantUML and Graphviz sources |
| `diagrams/erd.png` | Developers | Entity relationship diagram: green = business domain, red = authentication and authorisation, orange = framework (Flyway) |
| `screenshots/` | Testers | The 67 screenshots used in the Test Specification, named after their test case |

Each PDF has its Markdown source (`.md`) next to it, so the documents can be edited and
re-exported.

Re-render a diagram: `java -jar plantuml.jar -tpng -tsvg diagrams/source/<name>.puml`
(Graphviz needed), or `dot -Tpng -Gdpi=150 diagrams/source/dfd-*.dot`.
