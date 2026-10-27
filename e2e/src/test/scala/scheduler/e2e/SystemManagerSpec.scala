package scheduler.e2e

import org.openqa.selenium.By

/** The system manager sees everything, read-mostly so the demo data stays as it is. */
class SystemManagerSpec extends E2ESuite:
  private def tab(label: String): Unit = ui.click(ui.exact("button", label))

  describe("System manager") {
    it("sees every dealership on the platform") {
      ui.signIn("sysadmin@demo.vn")
      ui.waitForPath("/system")
      ui.see("Saigon Moto Care – District 1")
      ui.see("Riverside Auto & Moto – Thu Duc")
    }

    it("sees the appointments of every dealership in All data") {
      ui.open("/system/data")
      ui.see("All data")
      ui.visible(By.cssSelector("[data-appointment]"))
    }

    it("sees the bays and technicians of a dealership") {
      tab("Bays & technicians")
      ui.see("Stand A")
      ui.see("Diagnostic 1")
      ui.see("Trần Quốc Hùng")
      ui.see("Nguyễn Văn Minh")
    }

    it("sees every customer's vehicles with their owner") {
      tab("Vehicles")
      ui.see("59-X1 234.56")
      ui.see("customer@demo.vn")
    }

    it("sees customers and technicians under People") {
      ui.open("/system/people")
      ui.visible(By.cssSelector("[data-person='customer@demo.vn']"))
      ui.click(By.xpath("//button[@role='tab' and starts-with(normalize-space(.),'Technicians')]"))
      ui.visible(By.cssSelector("[data-person='tech.hung@demo.vn']"))
    }

    it("sees the service package catalogue") {
      ui.open("/system/packages")
      ui.see("Service packages")
      ui.see("Essential maintenance")
      ui.visible(ui.exact("h3", "Add a package"))
      ui.signOut()
    }
  }
