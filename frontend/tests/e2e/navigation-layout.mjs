import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const dir=new URL('../../../.nexus/validation/navigation-ui/',import.meta.url);await mkdir(dir,{recursive:true});
const browser=await chromium.launch({headless:true});const report=[];
try{
  for(const width of [1500,1024,390]){
    const page=await browser.newPage({viewport:{width,height:900}});
    await page.goto('http://localhost:5173/rules');
    const mobile=width<768;
    if(mobile)await page.getByRole('button',{name:'Open navigation',exact:true}).click();
    const nav=page.getByRole('navigation',{name:'Main navigation'});
    await nav.getByRole('link',{name:'Infrastructure',exact:true}).waitFor();
    const bounds=await page.locator('.nexus-sidebar').boundingBox();
    assert.ok(bounds.width>=240,'Labels must have enough room without hover');
    const label=nav.getByRole('link',{name:'Infrastructure',exact:true});
    assert.equal(await label.locator('span').first().evaluate(el=>getComputedStyle(el).opacity),'1');
    await page.screenshot({path:fileURLToPath(new URL(`expanded-${width}.png`,dir)),fullPage:true});
    await nav.getByRole('button',{name:'Intelligence',exact:true}).click();
    await nav.getByRole('link',{name:'AI Insights',exact:true}).click();
    await page.waitForURL('**/intelligence');
    if(mobile){
      assert.equal(await page.locator('.nexus-nav-backdrop').count(),0);
      await page.getByRole('button',{name:'Open navigation',exact:true}).click();
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('.nexus-nav-backdrop').count(),0);
    }else{
      assert.equal(await nav.locator('a[aria-current="page"]').count(),1);
      await page.getByRole('button',{name:'Collapse navigation',exact:true}).click();
      await page.waitForTimeout(250); // Wait for the explicit 180ms width transition.
      assert.equal(Math.round((await page.locator('.nexus-sidebar').boundingBox()).width),72);
      await page.screenshot({path:fileURLToPath(new URL(`compact-${width}.png`,dir)),fullPage:true});
      await nav.getByRole('button',{name:'Workspace',exact:true}).click();
      await nav.getByRole('link',{name:'Overview',exact:true}).waitFor();
      await page.getByRole('button',{name:'Connection details',exact:true}).click();
      await page.getByText('Database',{exact:true}).waitFor();
    }
    report.push({width,sidebar:bounds,labelsVisible:true,navigationSucceeded:true,mobileDrawerChecked:mobile});await page.close();
  }
  await writeFile(new URL('report.json',dir),JSON.stringify(report,null,2));
  console.log('PASS: labelled sidebar, groups, route navigation, active state, compact mode and mobile close/Escape at1500/1024/390px.');
}finally{await browser.close();}
