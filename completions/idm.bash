# Bash completion template. `bin/idm completion bash` fills in the current CLI metadata.
# BEGIN IDM COMMANDS
_idm_completion_commands=()
# END IDM COMMANDS

_idm_completion_options() {
    case "$1" in
# BEGIN IDM OPTIONS
# END IDM OPTIONS
        *) return 1 ;;
    esac
}

_idm_completion_subcommands() {
    case "$1" in
# BEGIN IDM SUBCOMMANDS
# END IDM SUBCOMMANDS
        *) return 1 ;;
    esac
}

_idm_complete() {
    local cur command candidates
    COMPREPLY=()
    cur=${COMP_WORDS[COMP_CWORD]}

    if (( COMP_CWORD == 1 )); then
        COMPREPLY=( $(compgen -W "${_idm_completion_commands[*]}" -- "$cur") )
        return
    fi

    command=${COMP_WORDS[1]}
    if candidates=$(_idm_completion_subcommands "$command") && [[ -n "$candidates" && "$cur" != -* ]]; then
        COMPREPLY=( $(compgen -W "$candidates" -- "$cur") )
        return
    fi

    if [[ "$cur" == --* ]]; then
        candidates=$(_idm_completion_options "$command") || return
        COMPREPLY=( $(compgen -W "$candidates" -- "$cur") )
    fi
}

complete -o default -F _idm_complete idm bin/idm ./bin/idm
