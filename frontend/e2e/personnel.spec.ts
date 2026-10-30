import { test, expect } from "@playwright/test";

const companyId = "10000000-0000-0000-0000-000000000001";
const person = { id: "20000000-0000-0000-0000-000000000001", companyId, employeeNumber: "E-001", firstName: "Alice", lastName: "Example", hireDate: "2026-01-01", employmentStatus: "ACTIVE" };

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/personnel/**", async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    let status = 200;
    let data: unknown = [];
    if (pathname.endsWith("/employees") && request.method() === "GET") {
      data = [person];
    } else if (pathname.endsWith("/employees") && request.method() === "POST") {
      status = 201;
      data = { ...person, id: "20000000-0000-0000-0000-000000000002", ...request.postDataJSON() };
    } else if (pathname.endsWith("/departments")) {
      data = [{ id: "3", code: "OPS", name: "Operations", active: true }];
    } else if (pathname.endsWith("/positions")) {
      data = [{ id: "4", code: "DEV", name: "Developer", active: true }];
    } else if (pathname.endsWith("/profile")) {
      data = { ...person, employmentType: "REGULAR", preferredName: "Ali" };
    } else {
      status = 404;
      data = { message: "Not found" };
    }
    await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(data) });
  });
});

async function connect(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page.getByLabel("Company UUID").fill(companyId);
  await page.getByLabel("MFA-verified bearer token").fill("ci-token-in-memory");
  await page.getByRole("button", { name: "Refresh directory" }).click();
  await expect(page.getByRole("cell", { name: "Alice Example" })).toBeVisible();
}

test("loads company-scoped directory and organization catalogs", async ({ page }) => {
  await connect(page);
  await expect(page.getByText("Operations")).toBeVisible();
  await expect(page.getByText("Developer", { exact: true })).toBeVisible();
  await expect(page.getByText("Directory synchronized.")).toBeVisible();
  await page.getByRole("button", { name: "Profile" }).click();
  await expect(page.getByRole("heading", { name: "Employee profile" })).toBeVisible();
  await expect(page.getByText("Ali")).toBeVisible();
});

test("registers employee in the connected company", async ({ page }) => {
  await connect(page);
  await page.getByLabel("Employee number").fill("E-002");
  await page.getByLabel("First name").fill("Bob");
  await page.getByLabel("Last name").fill("Example");
  await page.getByRole("button", { name: "Create employee" }).click();
  await expect(page.getByRole("cell", { name: "Bob Example" })).toBeVisible();
  await expect(page.getByText("Registered Bob Example.")).toBeVisible();
});

test("rejects directory request before credentials are supplied", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Refresh directory" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Create employee" })).toBeDisabled();
});
