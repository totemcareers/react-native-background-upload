if [ -z "${BG_UPLOAD_HEROKU_REMOTE}" ] 
then
  echo Please add your heroku remote into an env variable named \"BG_UPLOAD_HEROKU_REMOTE\"
  exit 1
fi


function cleanup {
  echo "Cleaning up..."
  rm -rf .git
}

trap cleanup EXIT

git init --quiet
git remote add heroku "${BG_UPLOAD_HEROKU_REMOTE}"
git add .
git commit -am "deploy"
git push --force heroku HEAD:master