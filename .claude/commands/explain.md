# /explain — Explain a File or Concept

Explains a project file or technical concept in clear terms, aimed at someone who is learning.

## Usage
- `/explain Encoder`
- `/explain TaskQueueService`
- `/explain fencing token`
- `/explain the attempt counter`
- `/explain rebase`

## Step 1: Determine whether it is a project file or a general concept

**If it is a project file:**
Find it under `protocol/src/main/java/`, `server/src/main/java/`, or `worker/src/main/java/` and read it completely before responding. If it doesn't exist yet, say so and offer to explain the design instead by reading the relevant section of `docs/DESIGN.md`.

**If it is a general concept** (WebSocket, fencing token, JWT, rebase, etc.):
Answer from general knowledge, using concrete examples from `docs/DESIGN.md` where possible (e.g. explain "fencing token" using ClipHive's `attempt` counter from section 6.3).

## Step 2: Structure of the explanation

Adapt the level to a junior developer working on this project. Follow this order:

### What is it? (1-2 sentences)
The shortest possible answer to "what is this for?".

### Why does it exist in this project?
The specific problem it solves. For a file: what would happen if it did not exist. Reference the `docs/DESIGN.md` risk (R1–R17) or decision it addresses, if any.

### How does it work? (the core)
Explain the flow step by step in simple language.
- Use real-world analogies when they help
- For Java files: explain the main methods one by one
- Point out the most important lines or sections with the line number

### How does it connect with the rest of the project?
What calls it, what it calls, which module it lives in and why, what it depends on.

### What does it NOT do? (if applicable)
Boundaries or common misconceptions about its responsibility — especially module-boundary ones (e.g. "the server never renders, that's the worker's job").

### Concrete example (if applicable)
A real flow in the project that uses this piece (e.g. "when a worker reconnects after a dropped heartbeat, this is what happens...").

## Step 3: Offer to go deeper

At the end, offer to expand on any specific aspect.
