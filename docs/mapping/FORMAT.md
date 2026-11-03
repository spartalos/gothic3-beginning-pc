# Name mapping format

One entry per line, whitespace separated. Lines starting with `#` are comments.
Obfuscated names and JVM descriptors must be exact (check with `javap -p -s -classpath <extracted jar dir> <Class>`).

    class  <obfClass>  <newName>                                    [# comment]
    field  <obfClass>  <obfField>  <descriptor>  <newName>          [# comment]
    method <obfClass>  <obfMethod> <descriptor>  <newName>          [# comment]

Rules
- All game classes will move to package `g3`; give class names without a package (e.g. `GameCanvas3`, not `g3.GameCanvas3`).
- Never rename: methods that override/implement MIDP or JDK methods (paint, keyPressed, keyReleased, run, startApp, pauseApp, destroyApp, commandAction, playerUpdate, showNotify, hideNotify, sizeChanged, <init>, <clinit>), or anything in javax.*/java.*.
- Overloads: the same obfuscated name with different descriptors is a different method; map each separately.
- New names must be unique within a class (fields among fields, methods among methods with the same descriptor) and valid Java identifiers (no keywords).
- Only map what you are reasonably confident about. Unknown -> leave unmapped. Prefix uncertain-but-plausible names with nothing special; put doubt in the comment.
- lowerCamelCase for fields/methods, UpperCamelCase for classes, UPPER_SNAKE for static final constants.
