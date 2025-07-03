def jobName = 'AWS_AMI_Cleanup_Python-New'
def awsRegion = 'eu-central-1'
def amisToKeepList = """
jenkins-agent-image-ubuntu-22.04-1745255124
jenkins-agent-image-ceic_windows-2022-1737116604
jenkins-agent-image-ubuntu-18.04-1739260079
jenkins-agent-image-ubuntu-20.04-1739209223
jenkins-agent-image-ubuntu-22.04-test-1745255347
jenkins-agent-image-yocto-ubuntu-22.04-1745255127
jenkins-agent-image-gpu-ubuntu-22.04-1745255597
jenkins-agent-image-apricotui-ubuntu-22.04-1745255348
jenkins-agent-image-qnx-ubuntu-22.04-1745263239
jenkins-agent-image-vcpu-windows-2022-1737993715
jenkins-agent-win-vanilla-server-2022-1737987893
jenkins-agent-win-vcpu-vanilla-server-2022-1737990473
jenkins-admin-agent-image-ubuntu-22.04-1745255348
""".stripIndent().trim()

pipelineJob(jobName) {
    description("Deletes AWS AMIs and their associated snapshots, excluding a specified list of AMIs. Only processes AMIs owned by the current account.")
    parameters {
        stringParam('AWS_REGION', awsRegion, 'The AWS region where AMIs will be searched and deleted.')
        textParam('AMIS_TO_KEEP', amisToKeepList, 'Enter AMI names to KEEP, one per line. All other AMIs owned by the current account will be deleted.')
        choiceParam('DRY_RUN', ['true', 'false'], 'If true, no actual deletions will occur. Set to false to perform deletions.')
    }
    definition {
        cps {
            script("""
pipeline {
    agent { label 'suman' }

    environment {
        REGION = "\${params.AWS_REGION}"
        AMIS_TO_KEEP_LIST_STRING = "\${params.AMIS_TO_KEEP}"
        DRY_RUN_MODE = "\${params.DRY_RUN}"
    }

    stages {
        stage('Prepare Python Script') {
            steps {
                script {
                    writeFile file: 'cleanup_amis.py', text: '''import boto3
import os
from tabulate import tabulate

region = os.environ['REGION']
dry_run = os.environ['DRY_RUN_MODE'].lower() == 'true'
amis_to_keep = os.environ['AMIS_TO_KEEP_LIST_STRING'].splitlines()

ec2 = boto3.client('ec2', region_name=region)
account_id = boto3.client('sts').get_caller_identity()['Account']

images = ec2.describe_images(Owners=[account_id])['Images']
rows = []

for image in images:
    ami_id = image['ImageId']
    ami_name = image.get('Name', '')

    snapshots = [mapping.get('Ebs', {}).get('SnapshotId')
                 for mapping in image.get('BlockDeviceMappings', [])
                 if mapping.get('Ebs', {}).get('SnapshotId')]

    if ami_name in amis_to_keep:
        rows.append(['Skipping', ami_id, ami_name, ''])
        continue

    action = 'DRY RUN: Deregister' if dry_run else 'Deregistered'

    if not snapshots:
        rows.append([action, ami_id, ami_name, '-'])
    else:
        rows.append([action, ami_id, ami_name, snapshots[0]])
        for snap in snapshots[1:]:
            rows.append(['', '', '', snap])

    if not dry_run:
        ec2.deregister_image(ImageId=ami_id)
        for snapshot_id in snapshots:
            try:
                ec2.delete_snapshot(SnapshotId=snapshot_id)
            except Exception as e:
                rows.append(['Error Deleting Snapshot', ami_id, ami_name, f"{snapshot_id}: {e}"])

# Sort rows so 'Skipping' entries come first
rows_sorted = sorted(rows, key=lambda r: (r[0] != 'Skipping', r[2]))

# Print to console
print(tabulate(rows_sorted, headers=["Action", "AMI ID", "AMI Name", "Snapshot ID"], tablefmt="grid"))

# Write report to file
with open("ami_cleanup_report.txt", "w") as f:
    f.write(tabulate(rows_sorted, headers=["Action", "AMI ID", "AMI Name", "Snapshot ID"], tablefmt="grid"))
'''
                }
            }
        }

        stage('Run Cleanup Script') {
            steps {
                    sh 'python3 cleanup_amis.py'
            }
        }

        stage('Archive Report') {
            steps {
                archiveArtifacts artifacts: 'ami_cleanup_report.txt', onlyIfSuccessful: true
            }
        }
    }
}
            """)
            sandbox(false)
        }
    }
}
