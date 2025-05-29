const core = require('@actions/core');
const github = require('@actions/github');
const exec = require('@actions/exec');

module.exports = async ({ github, context }) => {
  try {
    // 示例：执行 `ls -la` 并获取输出
    let output = '';
    const options = {
      listeners: {
        stdout: (data) => {
          output += data.toString();
        }
      }
    };

    await exec.exec('ls', ['-la'], options);
    console.log('Command output:', output);

    // 示例：执行带变量的命令
    await exec.exec('echo', ['Hello, $USER'], { env: { USER: 'GitHub' } });

    // 示例：执行多行脚本
    await exec.exec('bash', ['-c', 'echo "Running a script" && ls']);

    // 其他 GitHub 操作（如评论 PR）
    const { pull_request: pr } = context.payload;
    const comment = `Command executed:\n\`\`\`\n${output}\n\`\`\``;

    await github.rest.issues.createComment({
      issue_number: pr.number,
      owner: context.repo.owner,
      repo: context.repo.repo,
      body: comment
    });
  } catch (error) {
    core.setFailed(`Error executing command: ${error.message}`);
  }
};