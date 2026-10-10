---
name: expense-log
description: Read a CSV of expenses and turn it into a categorized summary with totals per category and per month. Use when the user asks to review, summarize, categorize or log expenses from a CSV or spreadsheet export.
version: 1.0.0
author: unibot
tools: file_read, calculator
---

# Expense log

Read the user's expense CSV and produce a clear summary. Never invent transactions — everything reported comes from the file.

## Read

- Ask which file if it is not obvious; bank/statement CSVs are usually in Downloads. `file_read` the CSV.
- Detect the columns by header name (common: date, description/merchant, amount, category, currency). If there is no header, treat columns as date, description, amount and say so.
- Amounts may use either sign convention (negative = spend, or a separate debit/credit column). Pick the convention that makes the numbers sane and state it in one line.
- Skip transfer rows between the user's own accounts if they are marked as such; say how many were skipped.

## Categorize

Map each merchant/description to one of: Food, Transport, Shopping, Bills, Health, Entertainment, Travel, Other. Keep a small running map in memory ("Careem → Transport") so the next run is consistent; if the user corrects a category, remember the correction.

## Report

In chat, compact and skimmable:

**Expenses — <month or range>**
- Total: <amount> across <n> transactions
- By category: <category>: <amount> (<n>), one per line, largest first
- Largest single spend: <merchant> — <amount> on <date>
- Anything odd: duplicate charges, unusually large amounts, subscriptions renewed

Then ask: "Save this summary?" If yes, write it to `finance/expenses-YYYY-MM.md` (create the folder if needed) and remember the category map. Do not write anything until the user confirms.

If the CSV cannot be parsed, say which row broke and stop — do not guess the numbers.
