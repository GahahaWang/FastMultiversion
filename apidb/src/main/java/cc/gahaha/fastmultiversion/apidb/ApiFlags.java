package cc.gahaha.fastmultiversion.apidb;

/** The JVM access flags the database keeps (JVMS 4.1, 4.5, 4.6, 4.7.6), and visibility helpers. */
public final class ApiFlags {
    public static final int PUBLIC = 0x0001;
    public static final int PRIVATE = 0x0002;
    public static final int PROTECTED = 0x0004;
    public static final int STATIC = 0x0008;
    public static final int FINAL = 0x0010;
    public static final int INTERFACE = 0x0200;
    public static final int ABSTRACT = 0x0400;
    public static final int ANNOTATION = 0x2000;
    public static final int ENUM = 0x4000;

    /** Flags kept for classes; nested classes take theirs from the InnerClasses attribute. */
    public static final int CLASS_MASK = PUBLIC | PRIVATE | PROTECTED | STATIC | FINAL | INTERFACE | ABSTRACT | ANNOTATION | ENUM;
    /** Flags kept for fields and methods. */
    public static final int MEMBER_MASK = PUBLIC | PRIVATE | PROTECTED | STATIC | FINAL | ABSTRACT;

    private ApiFlags() {
    }

    /** 0 private, 1 package-private, 2 protected, 3 public. */
    public static int visibility(int access) {
        if ((access & PUBLIC) != 0) {
            return 3;
        }
        if ((access & PROTECTED) != 0) {
            return 2;
        }
        return (access & PRIVATE) != 0 ? 0 : 1;
    }

    public static boolean isStatic(int access) {
        return (access & STATIC) != 0;
    }

    public static boolean isInterface(int access) {
        return (access & INTERFACE) != 0;
    }
}
