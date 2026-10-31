import {test,expect} from '@playwright/test';

const COMPANY='10000000-0000-0000-0000-000000000001';
const CONFIG='20000000-0000-0000-0000-000000000001';

test('unauthenticated payroll page refuses API without exposing data',async({page})=>{
  await page.goto('/payroll');
  await expect(page.getByRole('heading',{name:'Payroll Management'})).toBeVisible();
  await expect(page.getByText(/NO-GO — configuration only/)).toBeVisible();
  await expect(page.getByRole('button',{name:'Create draft period'})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Create configuration'})).toBeDisabled();
  await page.getByLabel('Payroll company UUID').fill(COMPANY);
  await page.getByRole('button',{name:'Load payroll drafts'}).click();
  await expect(page.getByRole('alert')).toContainText('Sign in as an MFA-verified tenant administrator');
});

test('payroll configuration and period metadata stays draft-only',async({page})=>{
  await page.addInitScript(()=>sessionStorage.setItem('apexHrm.accessToken','a'.repeat(64)));
  const configs=[{id:CONFIG,companyId:COMPANY,payFrequency:'SEMI_MONTHLY',currencyCode:'PHP',timezone:'Asia/Manila',effectiveFrom:'2026-01-01'}];
  const drafts:[{id:string;companyId:string;configurationId:string;startsOn:string;endsOn:string;payOn:string;status:string;classification:string}] = [{
    id:'30000000-0000-0000-0000-000000000001',companyId:COMPANY,configurationId:CONFIG,
    startsOn:'2026-11-01',endsOn:'2026-11-15',payOn:'2026-11-20',
    status:'DRAFT',classification:'CONFIGURATION_ONLY_NOT_PAYROLL_APPROVED'
  }];
  await page.route('**/api/v1/payroll/**',async route=>{
    const {pathname}=new URL(route.request().url());
    if(pathname.endsWith('/configurations')){
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(configs)});
    }else if(pathname.endsWith('/periods')){
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(drafts)});
    }else{
      await route.fulfill({status:404,contentType:'application/json',body:'{}'});
    }
  });
  await page.goto('/payroll');
  await page.getByLabel('Payroll company UUID').fill(COMPANY);
  await page.getByRole('button',{name:'Load payroll drafts'}).click();
  await expect(page.getByText('SEMI_MONTHLY · PHP · 2026-01-01')).toBeVisible();
  await expect(page.getByText(/2026-11-01 – 2026-11-15/)).toBeVisible();
  await expect(page.getByText(/DRAFT/).last()).toBeVisible();
  await expect(page.getByRole('button',{name:/release|calculate|approve payroll/i})).toHaveCount(0);
});

test('login page still available without stored bearer token',async({page})=>{
  await page.goto('/');
  await expect(page.locator('body')).toContainText(/Apex|People/i);
  await expect(page.locator('input[type="password"]').first()).toBeVisible();
});
