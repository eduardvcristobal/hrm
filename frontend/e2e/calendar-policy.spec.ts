import { test, expect } from '@playwright/test';

test('M2.6 calendar administration shows classification preview and protected controls', async ({page}) => {
  await page.goto('/calendar');
  await expect(page.getByRole('heading',{name:'Holidays & Rest Days'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Add company calendar day'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Employee day override'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Employee calendar classification preview'})).toBeVisible();
  await expect(page.getByText(/No statutory holiday catalog, payable premiums/)).toBeVisible();
  await expect(page.getByRole('button',{name:'Save calendar day'})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Save employee override'})).toBeDisabled();
});
test('M2.6 calendar refuses unauthenticated API operations', async ({page}) => {
  await page.goto('/calendar');
  await page.getByPlaceholder('Company ID').fill('10000000-0000-0000-0000-000000000001');
  await page.getByPlaceholder('Employee ID').fill('20000000-0000-0000-0000-000000000002');
  await page.getByRole('button',{name:'Load calendar preview'}).click();
  await expect(page.getByRole('alert')).toContainText('Sign in and verify MFA');
});
test('timekeeping links to calendar administration', async ({page}) => {
  await page.goto('/timekeeping');
  await page.getByRole('link',{name:'Holidays & rest days'}).click();
  await expect(page).toHaveURL(/\/calendar$/);
});
