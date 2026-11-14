const fs=require('fs'),path=require('path');
const sharp=require('/Users/brainchild/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp');
(async()=>{const root=path.resolve(__dirname,'../diagrams');for(const name of fs.readdirSync(root).filter(x=>x.endsWith('.svg')))await sharp(path.join(root,name)).resize({width:1800}).png().toFile(path.join(root,name.replace('.svg','.png')));})();
