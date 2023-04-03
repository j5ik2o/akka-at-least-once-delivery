# Akka Persistence Typedの実装方法の改善について

## 目的

このドキュメントの目的は、Akka Persistence Typedの実装方法の改善についての提案です。

## 問題

Akka Persistence Typedの現在の実装では、次のような問題があります。

- 問題1: これまでのアクタープログラミングのスタイルとは大きく異なります。
- 問題2: 複雑な状態遷移を実装する場合は、match/caseが複雑になり保守性が低下します。
- 問題3: コマンドハンドラでは新しい状態に遷移することができないため、ドメインオブジェクトを使いにくい

この問題をわかりやすくするために、銀行口座集約を例にして解説します。

## 通常のアクタープログラミングではどうなるか

Behaviorを使って状態遷移を記述できます。

[BankAccountAggregate](https://github.com/j5ik2o/akka-at-least-once-delivery/blob/main/src/main/scala/example/persistence/styleInMemory/BankAccountAggregate.scala)


## Akka Persistence Typed での問題

- EventSourcedBehaviorに従う必要があるため、コマンドハンドラはBehaviorを返せません。漸進的な実装がしにくい。
- StateとCommandが複雑な場合はコマンドハンドラの保守性が下がります。→これについては分割して記述するなど対策はあります。
- 状態更新を扱えないとなると、ロジックをドメインオブジェクトに委譲しにくい。DDDとの相性がよくないです。

[BankAccountAggregate](https://github.com/j5ik2o/akka-at-least-once-delivery/blob/main/src/main/scala/example/persistence/styleDefault/BankAccountAggregate.scala)


## 新しい書き方の提案

- この方法ではEventSourcedBehaviorを集約アクターの子アクターとするため、上記の問題を解消できます。
- 通常のアクタープログラミングの実装方法をそのまま適用可能です。
- 通常のアクタープログラミングの実装から永続化対応されることが比較的容易です。
- 実装例では完全なインメモリモードを提供しているので、初期実装を書く上ではAkka Persistence Typedさえ不要になります。

[BankAccountAggregate](https://github.com/j5ik2o/akka-at-least-once-delivery/blob/main/src/main/scala/example/persistence/styleEffector/BankAccountAggregate.scala)


## まとめ

以下のようなケースに当てはまる場合はこの方法を検討してください。

- 最初から永続化を考慮しないでステップバイステップで実装する場合は、新しい書き方をする
- 状態遷移が複雑な場合は、新しい書き方をする
