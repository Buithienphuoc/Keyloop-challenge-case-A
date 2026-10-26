package scheduler.e2e

import org.openqa.selenium.By

/** Dealership administration and platform management, read-mostly so the demo data stays as it is. */
class BackOfficeSpec extends E2ESuite:
  describe("Dealership admin (District 1)") {
    it("lists the service bays") {
      ui.signIn("admin.d1@demo.vn")
      ui.open("/admin")
      ui.click(ui.exact("button", "Service bays"))
      ui.see("Stand A")
      ui.see("Diagnostic 1")
    }

    it("lists the technicians with their skills") {
      ui.click(ui.exact("button", "Technicians"))
      ui.see("Trần Quốc Hùng")
      ui.see("Nguyễn Văn Minh")
    }

    it("saves the opening hours and is told how many bookings fall outside them") {
      ui.click(ui.exact("button", "Opening hours"))
      ui.see("Opening hours and booking rules")
      // Saving the current values unchanged: proves the round trip without changing the demo.
      ui.clickButton("Save hours")
      ui.see("Saved.")
      ui.see("No upcoming booking is affected.")
    }

    it("can open the workload and parts pages too") {
      ui.open("/staff/workload")
      ui.see("Technicians")
      ui.open("/parts")
      ui.click(ui.exact("button", "Stock"))
      ui.visible(By.cssSelector("table.table tbody tr"))
      ui.signOut()
    }
  }

  describe("System manager") {
    it("sees every dealership on the platform") {
      ui.signIn("sysadmin@demo.vn")
      ui.waitForPath("/system")
      ui.see("Saigon Moto Care – District 1")
      ui.see("Riverside Auto & Moto – Thu Duc")
    }

    it("sees the service package catalogue") {
      ui.open("/system/packages")
      ui.see("Service packages")
      ui.see("Essential maintenance")
      ui.visible(ui.exact("h3", "Add a package"))
      ui.signOut()
    }
  }
