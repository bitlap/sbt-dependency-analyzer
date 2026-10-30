// Keep an already published version unchanged when master receives more commits.
module.exports = async function releaseStatus({ github, context, core, version }) {
  if (typeof version !== 'string' || version !== version.trim() || !/^\d+\.\d+\.\d+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?$/.test(version)) {
    throw new Error('Invalid project release version');
  }
  const tag = `v${version}`;
  try {
    const { data } = await github.rest.repos.getReleaseByTag({ ...context.repo, tag });
    core.setOutput('should-draft', data.draft ? 'true' : 'false');
    if (!data.draft) core.info(`${tag} is already published; keeping the release unchanged.`);
  } catch (error) {
    if (error.status !== 404) throw error;
    core.setOutput('should-draft', 'true');
  }
};
