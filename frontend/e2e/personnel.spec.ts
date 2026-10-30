import { test, expect } from '@playwright/test';

test('staff login renders credential controls', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByLabel('Email or username')).toBeVisible();
  await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Create a workspace' })).toBeVisible();
});

test('password visibility toggle is accessible', async ({ page }) => {
  await page.goto('/');
  const password=page.getByLabel('Password', { exact: true });
  await expect(password).toHaveAttribute('type','password');
  await page.getByRole('button',{name:'Show password'}).click();
  await expect(password).toHaveAttribute('type','text');
  await page.getByRole('button',{name:'Hide password'}).click();
  await expect(password).toHaveAttribute('type','password');
});

test('account recovery is available from staff login', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button',{name:'Forgot password?'}).click();
  await expect(page.getByRole('heading',{name:'Reset your password'})).toBeVisible();
  await expect(page.getByLabel('Work email')).toBeVisible();
  await page.getByRole('button',{name:'Close'}).click();
  await expect(page.getByRole('heading',{name:'Reset your password'})).not.toBeVisible();
});

test('unknown dashboard access presents sign-in', async ({ page }) => {
  await page.goto('/dashboard');
  await expect(page.getByLabel('Email or username')).toBeVisible();
});
