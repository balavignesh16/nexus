import {chromium} from '../frontend/node_modules/playwright/index.mjs';
import {fileURLToPath} from 'node:url';
const browser=await chromium.launch({headless:true});
try{const page=await browser.newPage({viewport:{width:1700,height:1250}});await page.goto('http://localhost:5173/simulation');await page.getByRole('heading',{name:'DEMO-ESP3 Automation Room'}).waitFor();await page.screenshot({path:fileURLToPath(new URL('../.nexus/validation/three-esp-demo/current-ui.png',import.meta.url)),fullPage:true});}finally{await browser.close();}
