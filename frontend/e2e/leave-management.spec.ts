import { test, expect } from '@playwright/test';

test('leave management renders controlled administration workflow',async({page})=>{
  await page.goto('/leave');
  await expect(page.getByRole('heading',{name:'Leave Management'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Create leave category'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Submit leave request'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Employee leave requests'})).toBeVisible();
  await expect(page.getByText('Entitlement balances, accrual, paid leave and payroll effects are not implemented')).toBeVisible();
  await expect(page.getByRole('button',{name:'Create category'})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Submit for review'})).toBeDisabled();
});

test('leave management rejects unauthenticated requests without contacting the API',async({page})=>{
  await page.goto('/leave');
  await page.getByPlaceholder('Company ID').fill('10000000-0000-0000-0000-000000000001');
  await page.getByRole('button',{name:'Load leave types'}).click();
  await expect(page.getByRole('alert')).toContainText('Sign in and complete MFA');
});

test('timekeeping screen links to leave management',async({page})=>{
  await page.goto('/timekeeping');
  await page.getByRole('link',{name:'Leave management'}).click();
  await expect(page).toHaveURL(/\/leave$/);
});
