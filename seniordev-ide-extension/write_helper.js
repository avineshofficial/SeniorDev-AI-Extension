const fs = require('fs');
const path = require('path');
const targetPath = 'S:\\seniordev-ai\\seniordev-ide-extension\\src\\ui\\ChatViewProvider.ts';
const content = fs.readFileSync(process.argv[2], 'utf8');
fs.writeFileSync(targetPath, content, 'utf8');
console.log('Written ' + content.length + ' bytes');
