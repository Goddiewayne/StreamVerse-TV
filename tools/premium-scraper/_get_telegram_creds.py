"""Extract Telegram API credentials from Chrome session using Playwright."""
import os
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

# Common Chrome profile paths
chrome_profiles = [
    Path(os.environ.get("LOCALAPPDATA", "")) / "Google" / "Chrome" / "User Data",
    Path(os.environ.get("LOCALAPPDATA", "")) / "Google" / "Chrome" / "User Data" / "Default",
]

profile_path = None
for p in chrome_profiles:
    if p.exists():
        profile_path = str(p.parent) if p.name == "Default" else str(p)
        print(f"Found Chrome profile: {profile_path}")
        break

if not profile_path:
    print("No Chrome profile found!")
    exit(1)

with sync_playwright() as p:
    browser = p.chromium.launch_persistent_context(
        user_data_dir=profile_path,
        headless=True,
        args=["--no-sandbox"],
    )
    page = browser.new_page()
    
    # Navigate to my.telegram.org/apps
    print("Navigating to my.telegram.org/apps...")
    page.goto("https://my.telegram.org/apps", timeout=30000, wait_until="domcontentloaded")
    
    # Wait for page to load
    page.wait_for_timeout(3000)
    
    content = page.content()
    title = page.title()
    print(f"Page title: {title}")
    url = page.url
    print(f"Current URL: {url}")
    
    # Check if we're already on the apps page (logged in)
    if "apps" in url and "login" not in url.lower():
        print("Already logged in! Looking for API credentials...")
        # Try to find api_id and api_hash in the page
        body_text = page.locator("body").inner_text()
        print(body_text[:2000])
        
        # Look for api_id in input fields
        api_id_input = page.locator("input[name='api_id'], input#api_id").first
        if api_id_input.is_visible():
            api_id = api_id_input.input_value()
            print(f"\nAPI_ID: {api_id}")
        
        api_hash_input = page.locator("input[name='api_hash'], input#api_hash").first
        if api_hash_input.is_visible():
            api_hash = api_hash_input.input_value()
            print(f"API_HASH: {api_hash}")
    else:
        print("Not logged in. Trying to check if session exists...")
        print(f"Page content (first 1000 chars): {content[:1000]}")
    
    # Save a screenshot for debugging
    page.screenshot(path="telegram_login.png")
    print("\nScreenshot saved to telegram_login.png")
    
    browser.close()
