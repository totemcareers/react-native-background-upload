module.exports = {
  root: true,
  extends: '@react-native-community',
  parserOptions: {
    babelOptions: {
      configFile: __dirname + '/babel.config.js',
    },
  },
  overrides: [
    {
      files: ['*.js'],
      parser: '@babel/eslint-parser',
      plugins: ['@babel', 'flowtype'],
    },
  ],
};
