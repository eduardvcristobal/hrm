import {test,expect} from '@playwright/test';

test('M2.9.1 employee device enrollment remains non-attendance and requires review',async({page})=>{
  await page.addInitScript(()=>sessionStorage.setItem('apexHrm.accessToken','a'.repeat(64)));
  await page.route('**/api/v1/employee-self-service/me',route=>route.fulfill({
    status:200,contentType:'application/json',body:JSON.stringify({
      employee:{employeeId:'employee1',companyId:'company1',employeeNumber:'E100',firstName:'Test',lastName:'Employee',employmentStatus:'ACTIVE'},
      schedules:[],leaveRequests:[],attendanceSessions:[],availableLeaveCategories:[],
      clockInEnabled:false,locationCaptureEnabled:false,biometricCaptureEnabled:false,
      classification:'VERIFIED_EMPLOYEE_NO_DEVICE_CAPTURE'
    })
  }));
  await page.route('**/api/v1/employee-self-service/devices',route=>route.fulfill({
    status:200,contentType:'application/json',body:JSON.stringify([])
  }));
  await page.goto('/my-timekeeping');
  await page.getByRole('button',{name:'Load my timekeeping'}).click();
  await expect(page.getByRole('heading',{name:'My devices — enrollment only'})).toBeVisible();
  await expect(page.getByText(/Clock-in\/out, biometrics and location capture remain disabled/)).toBeVisible();
  await expect(page.getByRole('button',{name:'Request device enrollment'})).toBeDisabled();
  await page.getByLabel('My device description').fill('Test work laptop');
  await expect(page.getByRole('button',{name:'Request device enrollment'})).toBeEnabled();
  await expect(page.getByRole('button',{name:'Clock in'})).toHaveCount(0);
});

test('M2.9.1 HR device approval actions require an employee selection',async({page})=>{
  await page.goto('/employee-access');
  await expect(page.getByRole('heading',{name:'Independent device enrollment review'})).toBeVisible();
  await expect(page.getByRole('button',{name:'Load employee device requests'})).toBeDisabled();
  await expect(page.getByText(/do not authorize punches, GPS or biometrics/)).toBeVisible();
});
