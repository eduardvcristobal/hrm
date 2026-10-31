import { test, expect } from '@playwright/test';

test('M2.7 reconciliation screen labels evidence as internal, non-payroll',async({page})=>{
  await page.goto('/reconciliation');
  await expect(page.getByRole('heading',{name:'Attendance Reconciliation'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Reconciliation window'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Internal reconciliation packages'})).toBeVisible();
  await expect(page.getByText(/Not a payroll run/)).toBeVisible();
  await expect(page.getByRole('button',{name:'Save internal snapshot'})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Preview source evidence'})).toBeDisabled();
});
test('M2.7 refuses to preview evidence without authentication',async({page})=>{
  await page.goto('/reconciliation');
  await page.getByPlaceholder('Employee ID').fill('00000000-0000-0000-0000-000000000001');
  await page.getByRole('button',{name:'Preview source evidence'}).click();
  await expect(page.getByRole('alert')).toContainText('Sign in and verify MFA');
});
test('timekeeping navigation links to reconciliation',async({page})=>{
  await page.goto('/timekeeping');
  await page.getByRole('link',{name:'Attendance reconciliation'}).click();
  await expect(page).toHaveURL(/\/reconciliation$/);
});
