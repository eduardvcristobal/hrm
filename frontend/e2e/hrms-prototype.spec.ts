import { test, expect } from '@playwright/test';

test('prototype dashboard and full sidebar navigation render without authentication', async ({page}) => {
  await page.goto('/prototype');
  await expect(page.getByRole('heading', {name:/Good morning, Jordan/})).toBeVisible();
  await expect(page.getByText('Total employees')).toBeVisible();
  await expect(page.getByRole('button', {name:/Employee Directory/})).toBeVisible();
  await expect(page.getByRole('button', {name:/Leave Management/})).toBeVisible();
  await page.getByRole('button', {name:/Employee Directory/}).click();
  await expect(page).toHaveURL(/\/prototype\/employees/);
  await expect(page.getByRole('heading', {name:'People directory'})).toBeVisible();
  await expect(page.getByText('Sofia Reyes').first()).toBeVisible();
  await page.getByRole('button', {name:/Approval Center/}).click();
  await expect(page).toHaveURL(/\/prototype\/approvals/);
  await expect(page.getByText('Attendance correction').first()).toBeVisible();
});
test('employee search, detail, account menu and exit function', async ({page}) => {
  await page.goto('/prototype/employees');
  await page.getByPlaceholder('Search name, team, job title...').fill('Sofia');
  await expect(page.getByText('Sofia Reyes').first()).toBeVisible();
  await expect(page.getByText('Liam Santos')).toHaveCount(0);
  await page.getByRole('button', {name:/Sofia Reyes/}).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog').getByText('EMP-1001')).toBeVisible();
  await page.getByRole('button', {name:'Close employee details'}).click();
  await page.getByRole('button', {name:'Open account menu'}).click();
  await expect(page.getByRole('button', {name:/Exit prototype \/ Log out/})).toBeVisible();
  await page.getByRole('button', {name:/Exit prototype \/ Log out/}).click();
  await expect(page).toHaveURL(/\/$/);
});
test('all module navigation destinations show appropriate page title', async ({page}) => {
  const routes = ['onboarding','organization','documents','attendance','schedules','leave',
    'payroll','benefits','recruitment','performance','training','analytics',
    'approvals','announcements','audit','team','settings'];
  for (const route of routes) {
    await page.goto('/prototype/'+route);
    await expect(page.locator('.hp-page-head h1')).toBeVisible();
    await expect(page.getByText('INTERACTIVE PROTOTYPE')).toBeVisible();
  }
});
test('small screen navigation drawer opens, navigates, and closes', async ({page}) => {
  await page.setViewportSize({width:390,height:844});
  await page.goto('/prototype');
  await page.getByRole('button', {name:'Open navigation'}).click();
  await expect(page.locator('.hp-sidebar')).toHaveClass(/hp-sidebar-open/);
  await page.getByRole('button', {name:/Leave Management/}).click();
  await expect(page).toHaveURL(/\/prototype\/leave/);
  await expect(page.locator('.hp-sidebar')).not.toHaveClass(/hp-sidebar-open/);
});
