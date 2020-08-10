Become one of the contributors! We thrive to build a welcoming and open community for anyone who wants to use Mongoose
or contribute to it.
[Here](https://github.com/thecodeteam/codedellemc.github.io/wiki/How-to-contribute-to-%7Bcode%7D-and-add-your-project)
we describe how to contribute to any of the {code} projects.

Please, note that all contributors shall follow the Contributor Agreement guidelines
[provided here](https://github.com/thecodeteam/codedellemc.github.io/wiki/Contributor-Agreement).

# Contents

1. [Contributors](#1-contributors)<br/>
2. [Versions](#2-versions)<br/>
2.1. [Backward Compatibility](#21-backward-compatibility)<br/>
2.2. [Numbers](#22-numbers)<br/>
3. [Issues](#3-issues)<br/>
3.1. [States](#31-states)<br/>
3.2. [Specific Properties](#32-specific-properties)<br/>
4. [Continuous Integration](#4-continuous-integration)<br/>
4.1. [Build](#41-build)<br/>
4.2. [Testing](#42-testing)<br/>
4.3. [Releasing](#43-releasing)<br/>
5. [Code](#5-code)<br/>
5.1. [Style](#51-style)<br/>
5.2. [Performance](#52-performance)<br/>

# 1. Contributors

Alphabetically:

* [Andrey Kurilov](https://github.com/akurilov)
* [Anton Yurkov](https://github.com/orgs/emc-mongoose/people/yurkov-anton)
* [Alexander Aphonin](https://github.com/dadlex)
* [Evgeny Timitshuk](https://github.com/zhenytim)
* [Igor Konev](https://github.com/hoklet)
* [Veronika Kochugova](https://github.com/veronikaKochugova)
* [Vladislav Kulakovsky](https://github.com/vladislav-kulakovsky)

# 2. Versions

## 2.1. Backward Compatibility

The following interfaces are mentioned as the subject of the backward compatibility:
1. Input (item list files, scenario files, configuration options)
2. Output files containing the metrics
3. API

## 2.2. Numbers

Mongoose uses the [semantic versioning](http://semver.org/). This means that the ***X.Y.Z*** version notation is used:

* ***X***<br/>
    Major version number. Points to significant design and interfaces change. The *backward compatibility* is **not
    guaranteed**.
* ***Y***<br/>
    Minor version number. The *backward compatibility* is guaranteed.
* ***Z***<br/>
    Patch version number. Includes only the defect fixes.

# 3. Issues

Types:
* Defect
* Story
* Task
* Sub-task

| Type     | Description |
|----------|-------------|
| Defect   | The defect/bug which **affects the released version** (the type "Task" should be used if a defect/bug affects the version which is not released yet) |
| Story    | High-level use case or a long-term activity aspect (testing, performance, etc) |
| Task     | A task which couldn't be included into any defect/story |
| Sub-task | A task which could be included into a defect/story |

Tracker link: https://mongoose-issues.atlassian.net/projects/PRAVEGA

## 3.1. States

| State       | Description |
|-------------|-------------|
| OPEN        | All new issues should have this state. The issues are selected from the set of the *OPEN* issues for the proposal and review process. The task is updated w/ the corresponding comment but left in the *OPEN* state if it's considered incomplete/incorrect. Also incomplete/incorrect issue should be assigned back to the reporter.
| IN PROGRESS | The issue is in progress currently either initially done and the corresponding merge request to the `master` branch is created
| RESOLVED    | Issue is done and the corresponding changes are merged into the `master` branch
| CLOSED      | The new version is released containing the corresponding changes

**Note**:
> The corresponding impact probability/frequency is not taken into account in the process currently. For example, all
> defects are assumed to be equally frequently occurring and affecting same users, regardless the particular
> scenario/use case. This approach is used due to the lack of the sufficient statistical information about the Mongoose
> usage.

## 3.2. Specific properties

| Name                  | Applicable Issue Types | Who is responsible to specify  | Notes
|-----------------------|------------------------|--------------------------------|-------|
| Affected version      | Defect                 | Reporter: user/developer/owner | Only the *latest* version may be used for the defect reporting. The issue should be *rejected* if the reported version is not *latest*.
| Branch                | Defect, Task, Sub-task | Reviewer: developer/owner      |
| Description           | Task, Sub-task         | Reporter: user/developer/owner |
| Expected behaviour    | Defect                 | Reporter: user/developer/owner | The reference to the particular documentation part describing the expected behavior is preferable.
| Fix version           | Defect, Task, Sub-task | Reviewer: developer/owner      |
| Limitations           | Story                  | Reviewer: developer/owner      |
| Observed behaviour    | Defect                 | Reporter: user/developer/owner | Error message, errors.log output file, etc.
| Pull request          | Defect, Task, Sub-task | Reviewer: developer/owner      |
| Resolution commit     | Defect, Task, Sub-task | Reviewer: developer/owner      |
| Root cause            | Defect                 | Reviewer: developer/owner      |
| Start command/request | Defect                 | Reporter: user/developer/owner | Leave only the essential things to reproduce: try to check if possible if the bug is reproducible w/o distributed mode, different concurrency level, item data size, etc.
| Scenario              | Defect                 | Reporter: user/developer/owner | Don't clutter with large scenario files. Simplify the scenario leaving only the essential things.
| Steps                 | Defect                 | Reporter: user/developer/owner |
| Purpose               | Story                  | Reporter: user/developer/owner | Which particular problem should be solved with Mongoose? The links to the related documents and literature are encouraged.
| Requirements          | Story                  | Reporter: user/developer/owner | Both functional and performance requirements are mandatory. Optionally the additional requirements/possible enhancements may be specified.

# 4. Continuous Integration

https://gitlab.com/emc-mongoose/mongoose-storage-driver-pravega/pipelines

## 4.1. Build

```bash
./gradlew clean jar
```

The resulting jar file path is `./build/libs/mongoose-storage-driver-pravega-<VERSION>.jar`.

## 4.2. Testing

TODO

## 4.3. Releasing

1. Ensure all tests are OK
2. Ensure the new version documentation is ready
3. Share the testing build with QE and repeat this step until the qualification is OK
4. Create/replace the corresponding VCS tags:
   ```bash
   git tag -d latest
   git push origin :refs/tags/latest
   git tag -a <X>.<Y>.<Z> -m <X>.<Y>.<Z>
   git tag -a latest -m <X>.<Y>.<Z>
   git push --tags --force
   ```
5. Create the corresponding version branch `release-v<X>.<Y>.<Z>` and set the version in the configuration to
   <X>.<Y>.<Z>
6. Deploy docker images, set the `<X>.<Y>.<Z>` and `latest` tags also for the Docker images.
7. Upload the artifacts to the Central Maven repo:
   1.
    ```bash
    ./gradlew clean uploadArchives
    ```
   2. Go to the https://oss.sonatype.org/#stagingRepositories find the corresponding repository, close and then release
      it.

# 5. Code

## 5.1. Style

[Google Java Style](https://google.github.io/styleguide/javaguide.html) is used as default.

### Autoformatting hook

Git autoformatting hook reformats Java source code to comply with [Google Java Style](https://google.github.io/styleguide/javaguide.html).

The hook script is in the root of the repository named `pre-commit`.`pre-commit` **MUST be copied to the directory** `.git/hooks/` and you need to check that the script has the access permissions to execute:
```bash
ls -l pre-commit
```
If not, assign permissions for execution:
```bash
chmod +x pre-commit
```
This hook will work automatically with any commit and format the code in the same style.

### In addition:

* If interface is named `Foo` then:
  * Abstract implementation should be named as `FooBase`
  * Default concrete implementation should be names as `FooImpl`
* Any field/local variable should be *final* if possible

## 5.2. Performance
Take care about the performance in the ***critical*** places:
* Avoid *frequent* objects instantiation
* Avoid unnecessary *frequent* allocation
* Avoid *frequent* method calls if possible
* Avoid deep call stack if possible
* Avoid I/O threads blocking
* Avoid anonymous classes in the time-critical code
* Avoid non-static inner classes in the time-critical code
* Use thread locals (encryption, string builders)
* Use buffering, buffer everything
* Use batch processing if possible
