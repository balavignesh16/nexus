// Real UI layout regression; does not save, activate or dispatch any rule/action.
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const directory=new URL('../../../.nexus/validation/rule-builder-ui/',import.meta.url);
await mkdir(directory,{recursive:true});
const browser=await chromium.launch({headless:true});
const report=[];
try{
  for(const width of [1500,1024]){
    const page=await browser.newPage({viewport:{width,height:950}});
    const mutations=[];
    page.on('request',r=>{if(r.url().includes('/api/v1/')&&!['GET','OPTIONS'].includes(r.method()))mutations.push({method:r.method(),url:r.url()});});
    await page.goto('http://localhost:5173/rules');
    await page.getByRole('button',{name:'Visual Rule Builder',exact:true}).click();
    const workspace=page.getByRole('region',{name:'Visual rule builder',exact:true});
    await workspace.getByRole('heading',{name:'Build your first rule'}).waitFor();
    const box=await workspace.boundingBox();const header=await page.locator('header').first().boundingBox();
    assert.ok(box.y>=header.y+header.height,'Editor must not overlap global header');
    assert.ok(box.x>=90,'Editor must not cover global navigation');
    for(const name of ['Save','Validate','Simulate','Close visual studio']){
      const bounds=await workspace.getByRole('button',{name,exact:true}).boundingBox();
      assert.ok(bounds.x>=box.x&&bounds.x+bounds.width<=box.x+box.width+1,`${name} must fit in the workspace`);
    }
    const colors=await workspace.locator('.rule-studio').evaluate(el=>({background:getComputedStyle(el).backgroundColor,color:getComputedStyle(el).color}));
    assert.equal(colors.background,'rgb(241, 239, 248)');
    assert.equal(colors.color,'rgb(28, 27, 38)');
    assert.equal(await workspace.locator('.studio-toolbar').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(255, 255, 255)');
    assert.equal(await workspace.locator('.react-flow').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(241, 239, 248)');
    await page.screenshot({path:fileURLToPath(new URL(`empty-${width}.png`,directory)),fullPage:true});
    await workspace.getByRole('button',{name:'Add event trigger',exact:true}).last().click();
    await workspace.getByRole('button',{name:'Add condition',exact:true}).click();
    await workspace.getByRole('button',{name:'Add action',exact:true}).click();
    assert.equal(await workspace.locator('.react-flow__node').count(),3);
    await workspace.getByRole('button',{name:'Fit canvas',exact:true}).click();
    await page.waitForTimeout(900);
    await workspace.getByRole('button',{name:'Validate',exact:true}).click();
    await workspace.getByText('Validation Errors',{exact:true}).waitFor();
    await page.screenshot({path:fileURLToPath(new URL(`blocks-${width}.png`,directory)),fullPage:true});
    await workspace.getByRole('button',{name:'Close visual studio'}).click();
    await page.getByRole('button',{name:'Visual Rule Builder',exact:true}).waitFor();
    assert.equal(mutations.length,0,'Layout check must not save/activate or dispatch any rule');
    report.push({width,workspace:box,header,colors,blocksAdded:3,validationRejectedIncompleteGraph:true,backendMutations:mutations});
    await page.close();
  }
  await writeFile(new URL('report.json',directory),JSON.stringify(report,null,2));
  console.log('PASS: builder stays below header/beside navigation at 1500/1024px, foundation theme, click-to-add blocks, validation and return navigation; no backend writes.');
}finally{await browser.close();}
