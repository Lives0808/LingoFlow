import { main } from './cli';

const code = await main(process.argv);
if (code !== 0) process.exitCode = code;
