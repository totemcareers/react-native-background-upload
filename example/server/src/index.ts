import express from 'express';
import * as fs from 'fs';
import path from 'path';
import { inspect } from 'util';
const md5File = require('md5-file');

const router = express.Router();

router.get('/', (_, res) => {
  res.send('Hello World!');
});

router.post('/upload', (req, res) => {
  const filePath = path.join(__dirname, '/uploaded-file.txt');
  const stream = fs.createWriteStream(filePath);

  console.log(inspect(req.headers));
  console.log(filePath);

  if (req.query.simulateFailImmediately) {
    res.status(500).send('Simulated Error').end();
    return;
  }

  return new Promise<void>((resolve, reject) => {
    stream.on('open', () => {
      console.log('Stream open ...  0.00%');
      req.pipe(stream);
    });

    // Drain is fired whenever a data chunk is written.
    stream.on('drain', () => {
      // TODO simulate error while uploading
      const written = stream.bytesWritten;
      const total = parseInt(req.headers['content-length']!, 10);
      const progress = (written / total) * 100;

      console.log(`Processing  ...  ${progress.toFixed(2)}%`);
      if (req.query.simulateFailMidway && progress > 50) {
        res.status(500).send('Simulated Error').end();
        resolve();
      }
    });

    stream.on('close', () => {
      console.log('Processing  ...  100%');
      fs.promises.stat(filePath).then((r) => console.log(inspect(r)));
      md5File(filePath).then((r: string) => console.log('MD5:', r));
      res.send(filePath);
      resolve();
    });

    stream.on('error', (err) => {
      console.error(err);
      reject(err);
    });
  });
});

const app = express();
app.use(router);

app.listen(process.env.PORT || 3000, () => console.log('Server is running...'));
