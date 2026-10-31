import { test, expect } from '@playwright/test';

test('M2.8 reporting interface explains controlled exports and legal hold',async({page})=>{
  await page.goto('/timekeeping-reports');
  await expect(page.getByRole('heading',{name:'Timekeeping Reports & Retention'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Reporting context'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Retention planning — no deletion'})).toBeVisible();
  await expect(page.getByText(/deletion is disabled/)).toBeVisible();
  await expect(page.getByRole('button',{name:'Export attendance CSV'})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Create protected retention plan'})).toBeDisabled();
});
test('M2.8 unauthenticated audit export fails before serving CSV',async({page})=>{
  await page.goto('/timekeeping-reports');
  await page.getByRole('button',{name:'Export timekeeping audit CSV'}).click();
  await expect(page.getByRole('alert')).toContainText('Sign in and verify MFA');
});
test('M2.8 navigation from timekeeping opens reports',async({page})=>{
  await page.goto('/timekeeping');
  await page.getByRole('link',{name:'Reports & retention'}).click();
  await expect(page).toHaveURL(/\/timekeeping-reports$/);
});
