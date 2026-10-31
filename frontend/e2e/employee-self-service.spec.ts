import { test, expect } from '@playwright/test';

test('M2.9 employee self-service restricts device capture and private records',async({page})=>{
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

test('M2.9 verified employee can compose leave without selecting someone else',async({page})=>{
  await page.addInitScript(()=>sessionStorage.setItem('apexHrm.accessToken','a'.repeat(64)));
  let submitted:{leaveTypeId?:string;from?:string;to?:string;reason?:string;employeeId?:string}|null=null;
  await page.route('**/api/v1/employee-self-service/me',route=>route.fulfill({
    status:200,contentType:'application/json',body:JSON.stringify({
      employee:{employeeId:'e1',companyId:'c1',employeeNumber:'EMP001',firstName:'Test',lastName:'Employee',employmentStatus:'ACTIVE'},
      schedules:[],leaveRequests:[],attendanceSessions:[],
      availableLeaveCategories:[{id:'leave-type-1',code:'PERSONAL',name:'Personal Leave'}],
      clockInEnabled:false,locationCaptureEnabled:false,biometricCaptureEnabled:false,
      classification:'VERIFIED_EMPLOYEE_NO_DEVICE_CAPTURE'
    })
  }));
  await page.route('**/api/v1/employee-self-service/leave-requests',async route=>{
    submitted=route.request().postDataJSON() as typeof submitted;
    await route.fulfill({status:201,contentType:'application/json',body:JSON.stringify({
      id:'new-leave',leaveTypeId:'leave-type-1',startsOn:'2026-12-01',endsOn:'2026-12-02',status:'PENDING'
    })});
  });
  page.on('dialog',d=>d.accept());
  await page.goto('/my-timekeeping');
  await page.getByRole('button',{name:'Load my timekeeping'}).click();
  await expect(page.getByRole('heading',{name:'Submit my leave request'})).toBeVisible();
  await page.getByLabel('My leave category').selectOption('leave-type-1');
  await page.getByLabel('My leave from').fill('2026-12-01');
  await page.getByLabel('My leave to').fill('2026-12-02');
  await page.getByLabel('My leave reason').fill('Personal leave requested');
  await page.getByRole('button',{name:'Submit my leave request'}).click();
  await expect(page.getByRole('status')).toContainText('submitted for independent HR review');
  expect(submitted).toMatchObject({leaveTypeId:'leave-type-1',from:'2026-12-01',to:'2026-12-02'});
  expect(submitted).not.toHaveProperty('employeeId');
});
