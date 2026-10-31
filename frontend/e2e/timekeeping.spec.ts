import {test,expect} from '@playwright/test';

test('timekeeping workspace exposes schedule and attendance controls',async({page})=>{
  await page.goto('/timekeeping');
  await expect(page.getByRole('heading',{name:'Timekeeping & Attendance'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Create shift'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Assign schedule'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Attendance sessions'})).toBeVisible();
  await expect(page.getByRole('button',{name:'Clock in employee'})).toBeDisabled();
});

test('timekeeping requests refuse unauthenticated users before API calls',async({page})=>{
  await page.goto('/timekeeping');
  await page.getByPlaceholder('Company ID').fill('10000000-0000-0000-0000-000000000001');
  await page.getByRole('button',{name:'Load shifts'}).click();
  await expect(page.getByRole('status')).toContainText('Sign in and verify MFA');
});
