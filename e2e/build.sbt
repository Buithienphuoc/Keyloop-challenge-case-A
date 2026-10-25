// End-to-end tests: a real Chrome, driven by Selenium, clicks through the running web app.
// They need the whole system running (see README "End-to-end tests"):
//   docker compose --profile full up -d --build
//   cd e2e && sbt test                      # BASE_URL defaults to http://localhost:8080
ThisBuild / scalaVersion := "3.3.6"

lazy val e2e = (project in file("."))
  .settings(
    name := "service-scheduler-e2e",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "org.seleniumhq.selenium" % "selenium-java"   % "4.51.0" % Test,
      "org.scalatest"          %% "scalatest"       % "3.2.19" % Test,
      "ch.qos.logback"          % "logback-classic" % "1.5.18" % Test
    ),
    // One browser at a time: the suites share demo accounts and run in a fixed order.
    Test / parallelExecution := false,
    Test / fork := true,
    Test / javaOptions += "-Dstdout.encoding=UTF-8",
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD")
  )
