1. Duplicated code (重複的程式碼)
   Duplicated code is a code smell that occurs when the same or similar code appears in multiple places within a
   codebase. This can lead to maintenance issues, as changes made to one instance of the code may not be reflected in
   other instances, resulting in bugs and inconsistencies. Duplicated code can also make the codebase larger and more
   difficult to understand.

2. Long methods (過長的方法)
   Long methods are a code smell that occurs when a method contains too many lines of code or performs too many tasks.
   This can make the method difficult to understand and maintain, as it may be doing more than one thing. Long methods
   can also make it harder to test and debug, as it may be unclear which part of the method is responsible for a
   particular behavior.

Fix: Split Method or Extract Method

3. Large classes (過大的類別)
   Large classes are a code smell that occurs when a class contains too many methods or properties. This can make the
   class difficult to understand and maintain, as it may be doing more than one thing. Large classes can also make it
   harder to test and debug, as it may be unclear which part of the class is responsible for a particular behavior.

Fix: Extract Class

4. Long parameter lists (過長的參數列表)
   Long parameter lists are a code smell that occurs when a method or function has too many parameters. This can make
   the method or function difficult to understand and use, as it may be unclear what each parameter does. Long parameter
   lists can also make it harder to test and debug, as it may be unclear which parameter is responsible for a particular
   behavior.

Fix: Introduce Parameter Object or Use Builder Pattern

5. Feature envy (行為嫉妒)
   Feature envy is a code smell that occurs when a method or function is more interested in the data of another class
   than its own. This can indicate that the method or function is not properly encapsulated and may be violating the
   principle of information hiding. Feature envy can lead to maintenance issues, as changes made to one class may affect
   the behavior of another class that is envious of its features.

Fix: Extract Class or Introduce Parameter Object

6. Data clumps (資料團塊)
   Data clumps are a code smell that occurs when a group of data items are found together in various parts of a
   codebase, but they do not belong together. This can indicate that the data is not properly encapsulated and may be
   violating the principle of information hiding. Data clumps can lead to maintenance issues, as changes made to one
   part of the code may affect other parts that are using the same data.

Fix: Introduce Parameter Object or Extract Class

7. Unsuitable names (不適合的命名)
   Unsuitable names are a code smell that occurs when variables, methods, or classes are given names that do not
   accurately describe their purpose or behavior. This can make the code difficult to understand and maintain, as it may
   be unclear what the code is supposed to do. Unsuitable names can also lead to confusion and errors, as developers may
   misinterpret the purpose of the code based on its name.

8. Lack of comments (缺乏註解)
   Lack of comments is a code smell that occurs when code is not adequately documented with comments. This can make the
   code difficult to understand and maintain, as it may be unclear what the code is supposed to do or how it works. Lack
   of comments can also lead to confusion and errors, as developers may misinterpret the purpose of the code based on
   its structure or naming.

9. Unresolved warnings (未解決的警告)
   Unresolved warnings are a code smell that occurs when a codebase contains warnings that have not been addressed. This
   can indicate that the code is not being properly maintained and may be prone to bugs and errors. Unresolved warnings
   can also make it difficult to understand the code, as it may be unclear what the warnings are referring to or how
   they should be resolved.

10. Fat views (臃腫外表)
    Fat views are a code smell that occurs when a view in a software application contains too much logic or
    functionality. This can make the view difficult to understand and maintain, as it may be doing more than one thing.
    Fat views can also make it harder to test and debug, as it may be unclear which part of the view is responsible for
    a particular behavior.

11. Literal constants (字面常數)
    Literal constants are a code smell that occurs when hard-coded values are used in a codebase instead of defining
    them as constants or variables. This can make the code difficult to maintain, as changes to the value may require
    searching through the codebase to find all instances of the literal constant. Literal constants can also make the
    code less readable, as it may be unclear what the value represents or why it is being used.

12. Message chains (訊息鏈)
    Message chains are a code smell that occurs when a series of method calls are made on an object, where each method
    call returns another object that is then used for the next method call. This can make the code difficult to
    understand and maintain, as it may be unclear what the overall purpose of the message chain is. Message chains can
    also lead to maintenance issues, as changes made to one part of the chain may affect other parts that are using the
    same objects.

Fix: Hide Delegate or Introduce Local Extension

13. Abuse static methods and fields (濫用靜態方法和字段)
    Abusing static methods and fields is a code smell that occurs when static methods and fields are used excessively in
    a codebase. This can lead to maintenance issues, as static methods and fields can make it difficult to test and
    debug, as they may be shared across multiple parts of the codebase. Abusing static methods and fields can also make
    the code less flexible, as it may be difficult to change the behavior of the code without affecting other parts that
    are using the same static methods and fields.

Suitable use of static methods and fields can be beneficial for utility functions or constants that do not require
instance-specific behavior. However, it is important to use them judiciously and consider the implications for
maintainability and testability.
