import { test, expect } from '@playwright/test';

test('M2.9 employee self-service is explicitly read-only and private',async({page})=>{
  await page.goto('/my-timekeeping');
  await expect(page.getByRole('heading',{name:'My Timekeeping'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'My employee record'})).toBeVisible();
  await expect(page.getByText(/No employee clock-in, GPS\/location tracking, biometrics/)).toBeVisible();
  await expect(page.getByRole('button',{name:'Load my timekeeping'})).toBeEnabled();
  await expect(page.getByRole('button',{name:'Clock in'})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Submit leave'})).toHaveCount(0);
});

test('M2.9 employee without a verified session is refused before HTTP request',async({page})=>{
  await page.goto('/my-timekeeping');
  await page.getByRole('button',{name:'Load my timekeeping'}).click();
  await expect(page.getByRole('alert')).toContainText('Sign in with your employee account');
});
