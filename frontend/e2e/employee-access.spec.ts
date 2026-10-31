import {test,expect} from '@playwright/test';

test('M2.9 HR linking screen warns about verified identity, revocation and disabled devices',async({page})=>{
  await page.goto('/employee-access');
  await expect(page.getByRole('heading',{name:'Employee Access Linking'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Verified employee account'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Active self-service access'})).toBeVisible();
  await expect(page.getByText(/Device clock-in, geolocation and biometrics remain disabled/)).toBeVisible();
  await expect(page.getByRole('button',{name:'Link verified identity'})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Find active link'})).toBeDisabled();
});
test('M2.9 admin link lookup requires authenticated token',async({page})=>{
  await page.goto('/employee-access');
  await page.getByPlaceholder('Employee ID').fill('10000000-0000-0000-0000-000000000001');
  await page.getByRole('button',{name:'Find active link'}).click();
  await expect(page.getByRole('alert')).toContainText('Sign in as a tenant administrator');
});
test('M2.9 timekeeping navigation links to HR identity provisioning',async({page})=>{
  await page.goto('/timekeeping');
  await page.getByRole('link',{name:'Employee access'}).click();
  await expect(page).toHaveURL(/\/employee-access$/);
});
